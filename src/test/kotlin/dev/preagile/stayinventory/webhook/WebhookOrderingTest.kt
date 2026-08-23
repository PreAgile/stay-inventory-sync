package dev.preagile.stayinventory.webhook

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.inventory.InventoryFixture
import dev.preagile.stayinventory.persistence.InboundMessageRepository
import dev.preagile.stayinventory.persistence.ReservationRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import javax.sql.DataSource

/**
 * **순서 판정 (ADR-0013 · `#72`).**
 *
 * ## 무엇이 잘못돼 있었나
 *
 * `sequence_key` 는 `VARCHAR` 이고 정렬이 그 컬럼을 그대로 썼다. 문자열 정렬이므로
 * 숫자 리비전 `10` 이 `2` 보다 먼저 온다.
 *
 * ```
 *  sequence_key |  ev
 * --------------+------
 *  10           | 취소     ← 먼저 처리된다
 *  2            | 생성
 * ```
 *
 * 그러면 취소가 먼저 처리돼 예약이 없으니 `IGNORED` 되고, 이어서 생성이 확정되어
 * **최신 취소가 사라진다** — 팔리지 않아야 하는 방이 팔린 채 남는다.
 *
 * **순서 역전을 막으려는 정렬이 역전을 만들었다.**
 *
 * ## 이 스펙이 지키는 것 둘
 *
 * 정렬은 **한 배치 안에서만** 순서를 정한다. 배치를 넘는 역전은 묘비가 막는다.
 * 둘을 따로 검증한다 — 하나만 있으면 나머지 경로가 뚫린다.
 */
@SpringBootTest
@Import(PostgresTestContainer::class)
class WebhookOrderingTest(
    private val dataSource: DataSource,
    private val recorder: InboundMessageRecorder,
    private val worker: InboundMessageWorker,
    private val inbound: InboundMessageRepository,
    private val reservations: ReservationRepository,
    private val jdbc: JdbcTemplate,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)
    val fixture = InventoryFixture(dataSource)

    beforeTest { fixture.wipe() }

    fun created(roomTypeId: Long, rev: String) = ReservationWebhook(
        event = WebhookEvent.RESERVATION_CREATED,
        channelReservationId = "R-3001",
        sequenceKey = rev,
        roomTypeId = roomTypeId,
        checkIn = march1,
        checkOut = march1.plusDays(2),
        roomCount = 1,
        guestName = "김손님",
    )

    fun canceled(rev: String) = ReservationWebhook(
        event = WebhookEvent.RESERVATION_CANCELED,
        channelReservationId = "R-3001",
        sequenceKey = rev,
    )

    fun soldOn(date: LocalDate): Int =
        jdbc.queryForObject(
            "SELECT sold FROM daily_inventory WHERE stay_date = ?",
            Int::class.java,
            date,
        ) ?: -1

    // ── 정규화 ────────────────────────────────────────────────────────────
    test("#72 어댑터가 숫자 순서키를 비교 가능한 값으로 정규화한다") {
        // Given / When: 숫자 리비전을 받는다
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        recorder.record("CHANNEL_A", created(roomTypeId, rev = "2"))

        // Then: 원본은 그대로 남고 rank 가 따로 채워진다.
        // 원본을 덮으면 멱등 판정이 깨진다 -- 정규화가 두 값을 같은 rank 로
        // 뭉개도 서로 다른 알림이라는 사실은 남아야 한다
        val row = jdbc.queryForMap("SELECT sequence_key, sequence_rank FROM inbound_message")
        row["sequence_key"] shouldBe "2"
        (row["sequence_rank"] as Long) shouldBe 2L
    }

    test("#72 정규화할 수 없는 순서키는 rank 가 null 이다 — 그 사실을 숨기지 않는다") {
        // Given / When: 불투명 문자열
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        recorder.record("CHANNEL_A", created(roomTypeId, rev = "op-aq-77z"))

        // Then: null 이다. 추측해서 채우면 틀린 순서로 처리하고,
        // 그 틀림이 조용하다 -- 복원할 수 없다는 사실 자체가 설계 정보다
        val row = jdbc.queryForMap("SELECT sequence_key, sequence_rank FROM inbound_message")
        row["sequence_key"] shouldBe "op-aq-77z"
        row["sequence_rank"] shouldBe null
    }

    // ── 같은 배치 안의 역전 ───────────────────────────────────────────────
    test("#72 이미 취소된 예약의 낡은 생성은 중간 상태를 만들지 않는다") {
        // Given: 취소(rev 10)와 낡은 생성(rev 2)이 함께 들어와 있다
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        recorder.record("CHANNEL_A", canceled(rev = "10"))
        recorder.record("CHANNEL_A", created(roomTypeId, rev = "2"))

        // When
        worker.processPending()

        // Then: **예약을 만들지 않는다.** 만들었다가 취소하면 그 사이에 다른
        // 예약이 그 방을 볼 수 있고, outbox_event 가 두 번 나가 채널에 잘못된
        // 중간 상태를 보낸다 (ADR-0013 기각 대안 4).
        //
        // 문자열 정렬이던 시절에는 취소가 먼저 IGNORED 된 뒤 생성이 확정되어
        // **최신 취소가 사라졌다** -- 팔리지 않아야 하는 방이 팔린 채 남았다
        reservations.findAll().size shouldBe 0
        soldOn(march1) shouldBe 0
    }

    test("#72 순서키 없는 채널의 취소가 중복으로 버려지지 않는다 — 멱등 키에 event_type 이 있다") {
        // Given: 순서키 없는 채널의 생성
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        val create = ReservationWebhook(
            event = WebhookEvent.RESERVATION_CREATED,
            channelReservationId = "R-3001",
            sequenceKey = null,
            roomTypeId = roomTypeId,
            checkIn = march1,
            checkOut = march1.plusDays(2),
            roomCount = 1,
            guestName = "김손님",
        )
        recorder.record("CHANNEL_A", create) shouldBe RecordOutcome.Accepted(1L)
        worker.processPending()
        soldOn(march1) shouldBe 1

        // When: 같은 예약의 취소가 온다. 순서키가 없으므로 키의 셋째 칸이 둘 다 NULL
        val cancel = ReservationWebhook(
            event = WebhookEvent.RESERVATION_CANCELED,
            channelReservationId = "R-3001",
            sequenceKey = null,
        )
        val outcome = recorder.record("CHANNEL_A", cancel)

        // Then: event_type 이 키에 없으면 Duplicate 로 흡수되고 2xx 가 나간다.
        // 채널은 전달됐다고 믿고 다시 보내지 않으므로 **취소가 영구히 유실된다**
        (outcome is RecordOutcome.Accepted) shouldBe true
        worker.processPending()
        soldOn(march1) shouldBe 0
    }

    test("#72 같은 사건의 재전송은 여전히 중복이다 — event_type 을 넣어도 멱등은 유지된다") {
        // Given / When: 같은 취소를 두 번 보낸다
        fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        val cancel = canceled(rev = "7")
        recorder.record("CHANNEL_A", cancel)
        val second = recorder.record("CHANNEL_A", cancel)

        // Then: 키를 넓혔어도 같은 사건은 막아야 한다.
        // 넓히면서 멱등을 잃으면 #72 를 고치고 T3 를 깬 것이다
        second shouldBe RecordOutcome.Duplicate
        inbound.count() shouldBe 1
    }

    // ── 배치를 넘는 역전 — 묘비 ───────────────────────────────────────────
    test("#72 취소를 먼저 처리한 뒤 늦게 온 생성은 묘비가 막는다") {
        // Given: 취소(rev 10)가 먼저 도착해 처리된다. 예약이 없으므로 IGNORED
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        recorder.record("CHANNEL_A", canceled(rev = "10"))
        worker.processPending()
        reservations.findAll().size shouldBe 0

        // When: 다른 주기에 생성(rev 2)이 도착한다.
        // 정렬은 개입할 자리가 없다 -- 배치가 다르다
        recorder.record("CHANNEL_A", created(roomTypeId, rev = "2"))
        worker.processPending()

        // Then: 묘비가 없으면 예약이 확정되고 재고가 빠진다.
        // 팔리지 않아야 하는 방이 팔린 채 남는 상태다
        reservations.findAll().size shouldBe 0
        soldOn(march1) shouldBe 0
    }

    test("#72 취소보다 나중의 생성은 막지 않는다 — 재예약은 정상이다") {
        // Given: 취소(rev 2)가 먼저 처리된다
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        recorder.record("CHANNEL_A", canceled(rev = "2"))
        worker.processPending()

        // When: 더 높은 rank 의 생성(rev 10). 같은 예약 번호로 다시 잡은 경우다
        recorder.record("CHANNEL_A", created(roomTypeId, rev = "10"))
        worker.processPending()

        // Then: 묘비가 rank 를 안 보고 "취소가 있으면 막는다" 로 짜였다면
        // 정상 재예약까지 막힌다. 그것은 기회손실이다
        reservations.findAll().size shouldBe 1
        soldOn(march1) shouldBe 1
    }

    test("#72 rank 가 null 이면 묘비 검사를 하지 않는다 — 비교할 수 없는 값으로 판정하지 않는다") {
        // Given: 순서키가 없는 채널. 취소가 먼저 처리된다
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        recorder.record(
            "CHANNEL_A",
            ReservationWebhook(
                event = WebhookEvent.RESERVATION_CANCELED,
                channelReservationId = "R-3001",
                sequenceKey = null,
            ),
        )
        worker.processPending()

        // When: 순서키 없는 생성이 온다
        recorder.record(
            "CHANNEL_A",
            ReservationWebhook(
                event = WebhookEvent.RESERVATION_CREATED,
                channelReservationId = "R-3001",
                sequenceKey = null,
                roomTypeId = roomTypeId,
                checkIn = march1,
                checkOut = march1.plusDays(2),
                roomCount = 1,
                guestName = "김손님",
            ),
        )
        worker.processPending()

        // Then: 순서를 복원할 수 없으므로 도착 순서대로 확정된다.
        // 이 채널의 한계는 drift 검출이 받는다 -- 여기서 추측해서 막으면
        // 정상 예약을 잃고 그 손실이 조용하다
        reservations.findAll().size shouldBe 1
        inbound.count() shouldBe 2
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
