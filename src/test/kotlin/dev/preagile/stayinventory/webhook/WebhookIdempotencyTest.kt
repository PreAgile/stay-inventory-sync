package dev.preagile.stayinventory.webhook

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.domain.InboundStatus
import dev.preagile.stayinventory.domain.ReservationStatus
import dev.preagile.stayinventory.inventory.InventoryFixture
import dev.preagile.stayinventory.inventory.runConcurrentlyOrFail
import dev.preagile.stayinventory.persistence.InboundMessageRepository
import dev.preagile.stayinventory.persistence.OutboxEventRepository
import dev.preagile.stayinventory.persistence.ReservationRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.LocalDate
import javax.sql.DataSource

/**
 * **T3 — at-least-once 웹훅을 멱등하게 흡수한다.**
 *
 * ```
 * Given  동일한 channel_reservation_id 를 가진 예약 웹훅
 * When   10회 반복 수신 (일부는 동시)
 * Then   reservation 1건 · 재고 차감 1회 · 2회차 이후도 2xx
 * ```
 *
 * ## 멱등의 3중 방어 — 셋이 서로 다른 것을 막는다
 *
 * | 방어선 | 무엇을 막는가 | 없으면 |
 * |---|---|---|
 * | `inbound_message` UNIQUE | 같은 **알림**의 재수신 | 같은 알림이 여러 번 처리 큐에 쌓인다 |
 * | 애플리케이션 조기 반환 | 이미 그 **예약**이 있는 경우 | 순서키가 달라 UNIQUE 를 통과한 재전송이 두 번 차감한다 |
 * | `reservation` UNIQUE | 위 둘이 **경합으로 뚫린 경우** | 동시 처리에서 예약이 두 건 생긴다 |
 *
 * **하나가 다른 하나를 대신하지 못한다.** 첫째는 순서키가 다르면 통과하고,
 * 둘째는 읽기와 쓰기 사이가 열려 있고, 셋째는 예외로만 막는다.
 */
@SpringBootTest
@Import(PostgresTestContainer::class)
class WebhookIdempotencyTest(
    private val dataSource: DataSource,
    private val recorder: InboundMessageRecorder,
    private val worker: InboundMessageWorker,
    private val inbound: InboundMessageRepository,
    private val reservations: ReservationRepository,
    private val outbox: OutboxEventRepository,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)
    val fixture = InventoryFixture(dataSource)

    beforeTest { fixture.wipe() }

    fun webhook(roomTypeId: Long, sequenceKey: String? = null) = ReservationWebhook(
        event = WebhookEvent.RESERVATION_CREATED,
        channelReservationId = "R-2001",
        sequenceKey = sequenceKey,
        roomTypeId = roomTypeId,
        checkIn = march1,
        checkOut = march1.plusDays(2),
        roomCount = 1,
        guestName = "김손님",
    )

    test("T3 — 같은 알림 10회(일부 동시) 수신 후 예약 1건, 차감 1회") {
        // Given: 물리 10 인 격자
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)

        // When: 순차 5회 + 동시 5회. 채널의 재시도는 두 형태로 다 온다
        repeat(5) { recorder.record("CHANNEL_A", webhook(roomTypeId)) }
        runConcurrentlyOrFail(5) { recorder.record("CHANNEL_A", webhook(roomTypeId)) }

        // 첫 번째 방어선이 여기서 이미 9건을 흡수한다
        inbound.count() shouldBe 1

        worker.processPending()

        // Then
        reservations.count() shouldBe 1
        fixture.sold(roomTypeId, march1) shouldBe 1
        fixture.sold(roomTypeId, march1.plusDays(1)) shouldBe 1
        // 통보도 날짜당 하나씩만. 중복 수신이 통보를 부풀리면 채널이 같은 값을
        // 여러 번 받고, 그것은 레이트 리밋을 낭비한다
        outbox.count() shouldBe 2
    }

    test("T3 확장 — 순서키가 전부 달라 10건이 모두 적혀도 예약은 1건이다") {
        // Given: 순서키를 주는 채널이 같은 예약을 10번 재전송했다.
        // 첫 번째 방어선(UNIQUE)은 이것을 막지 못한다 -- 서로 다른 알림이다
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        repeat(10) { i -> recorder.record("CHANNEL_A", webhook(roomTypeId, sequenceKey = "rev-$i")) }
        inbound.count() shouldBe 10

        // When
        worker.processPending()

        // Then: 두 번째 방어선(이미 그 예약이 있으면 차감하지 않는다)이 막는다.
        // 이 자리가 없으면 재고가 10 빠진다
        reservations.count() shouldBe 1
        fixture.sold(roomTypeId, march1) shouldBe 1

        // 흡수된 9건은 실패가 아니라 IGNORED 다. 실패로 두면 재시도 큐가
        // 영원히 비지 않는다
        val statuses = inbound.findAll().groupingBy { it.status }.eachCount()
        statuses[InboundStatus.PROCESSED] shouldBe 1
        statuses[InboundStatus.IGNORED] shouldBe 9
    }

    test("워커를 두 번 돌려도 재고가 두 번 빠지지 않는다") {
        // Given: 처리까지 끝난 상태
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        recorder.record("CHANNEL_A", webhook(roomTypeId))
        worker.processPending()

        // When: 워커가 다시 돈다 (재기동·중복 스케줄)
        worker.processPending() shouldBe 0

        // Then: PENDING 이 아닌 알림은 다시 처리되지 않는다
        fixture.sold(roomTypeId, march1) shouldBe 1
    }

    test("처리 표시와 도메인 변경이 같은 트랜잭션이다 — 둘 다 되거나 둘 다 안 된다") {
        // Given: 격자가 없는 룸타입을 가리키는 알림. 처리 중에 예외가 난다
        fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        recorder.record(
            "CHANNEL_A",
            ReservationWebhook(
                event = WebhookEvent.RESERVATION_CREATED,
                channelReservationId = "R-broken",
                sequenceKey = null,
                // roomTypeId 를 비운다 -> requireNotNull 이 던진다
                roomTypeId = null,
                checkIn = march1,
                checkOut = march1.plusDays(1),
            ),
        )

        // When
        worker.processPending() shouldBe 0

        // Then: 예약도 안 생기고 처리 표시도 안 된다. 표시만 되면 그 알림은
        // 처리된 적 없이 영영 사라진다 -- 유실과 중복 중 중복을 택한다
        reservations.count() shouldBe 0
        inbound.findAll().single().status shouldBe InboundStatus.PENDING
    }

    test("취소 알림도 멱등하다 — 같은 취소가 여러 번 와도 한 번만 복원된다") {
        // Given: 확정된 예약
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        recorder.record("CHANNEL_A", webhook(roomTypeId))
        worker.processPending()
        fixture.sold(roomTypeId, march1) shouldBe 1

        // When: 취소 알림이 순서키를 바꿔 가며 5번 온다
        repeat(5) { i ->
            recorder.record(
                "CHANNEL_A",
                ReservationWebhook(
                    event = WebhookEvent.RESERVATION_CANCELED,
                    channelReservationId = "R-2001",
                    sequenceKey = "cancel-$i",
                ),
            )
        }
        worker.processPending()

        // Then: 조건부 UPDATE 의 rowcount 가 두 번째부터 0 이다
        fixture.sold(roomTypeId, march1) shouldBe 0
        reservations.findAll().single().status shouldBe ReservationStatus.CANCELED
    }

    test("재고가 없어 못 받는 예약은 IGNORED 로 남는다 — 무한 재시도하지 않는다") {
        // Given: 이미 매진인 날짜
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 1)
        fixture.markSoldOut(roomTypeId, march1)
        recorder.record("CHANNEL_A", webhook(roomTypeId))

        // When
        worker.processPending()

        // Then: 채널은 이미 예약을 확정했는데 우리 재고가 없다. 재시도해도
        // 결과가 같으므로 PENDING 으로 두면 큐가 영원히 안 빈다.
        // 어긋남을 기록으로 남기고 대사(#6)에 넘긴다
        inbound.findAll().single().status shouldBe InboundStatus.IGNORED
        reservations.count() shouldBe 1 // 픽스처가 만든 매진용 예약뿐이다
    }

    test("없는 예약의 취소 알림은 IGNORED 로 남는다 — 순서가 뒤집힌 경우다") {
        // Given: 생성 알림 없이 취소 알림만 도착했다. 웹훅은 순서 보장이 없다
        fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        recorder.record(
            "CHANNEL_A",
            ReservationWebhook(
                event = WebhookEvent.RESERVATION_CANCELED,
                channelReservationId = "R-unknown",
                sequenceKey = null,
            ),
        )

        // When
        worker.processPending()

        // Then: 지금 처리할 방법이 없다. PENDING 으로 남기면 무한 재시도가 된다
        inbound.findAll().single().status shouldBe InboundStatus.IGNORED
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
