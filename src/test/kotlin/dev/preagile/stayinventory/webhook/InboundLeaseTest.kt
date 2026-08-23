package dev.preagile.stayinventory.webhook

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.inventory.InventoryFixture
import dev.preagile.stayinventory.persistence.InboundMessageRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import javax.sql.DataSource

/**
 * Inbox 워커의 집기-임대 (`#66`).
 *
 * ## 무엇이 비대칭이었나
 *
 * 릴레이에는 집기-임대가 있고 Inbox 워커에는 없었다. **그 비대칭에 근거가 없었다.**
 *
 * 없어도 중복 **처리**는 막혔다 — 조기 반환과 `reservation` 의 `UNIQUE` 가 막는다.
 * 막히지 않은 것은 중복 **시도**이고, 그때 한쪽 트랜잭션은 예외로 롤백된 뒤
 * `runCatching` 이 삼킨다. **안전하지만 조용히 낭비했다.**
 *
 * ## 이 스펙이 보는 것
 *
 * **집힌 건수**다. 결과(예약 1건)만 보면 멱등이 중복을 가려 준다 —
 * 릴레이 다중 인스턴스 스펙이 어댑터 호출 횟수를 세는 것과 같은 이유다.
 */
@SpringBootTest(properties = ["inbox.worker.enabled=false"])
@Import(PostgresTestContainer::class)
class InboundLeaseTest(
    private val dataSource: DataSource,
    private val recorder: InboundMessageRecorder,
    private val inbound: InboundMessageRepository,
    private val jdbc: JdbcTemplate,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)
    val fixture = InventoryFixture(dataSource)

    beforeTest { fixture.wipe() }

    fun record(roomTypeId: Long, id: String) = recorder.record(
        "CHANNEL_A",
        ReservationWebhook(
            event = WebhookEvent.RESERVATION_CREATED,
            channelReservationId = id,
            sequenceKey = "1",
            roomTypeId = roomTypeId,
            checkIn = march1,
            checkOut = march1.plusDays(1),
            roomCount = 1,
            guestName = "김손님",
        ),
    )

    test("#66 집은 건은 다른 폴링에 다시 잡히지 않는다") {
        // Given: 알림 셋
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        repeat(3) { record(roomTypeId, "R-${it}") }

        // When: 두 인스턴스가 연달아 집는다
        val first = inbound.claimPending(10, 30)
        val second = inbound.claimPending(10, 30)

        // Then: 두 번째는 아무것도 못 집는다. 임대가 없으면 셋을 다시 집고,
        // 두 인스턴스가 같은 건을 동시에 처리한다
        first.size shouldBe 3
        second.size shouldBe 0
    }

    test("#66 만료된 임대는 다시 집힌다 — 죽은 인스턴스가 알림을 영구히 막지 못한다") {
        // Given: 집은 뒤 임대를 과거로 되돌린다 (인스턴스가 죽은 상태)
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        record(roomTypeId, "R-9")
        inbound.claimPending(10, 30).size shouldBe 1
        jdbc.update("UPDATE inbound_message SET leased_until = now() - interval '1 minute'")

        // When / Then: 만료됐으므로 다시 집힌다.
        // 회수 경로가 없으면 그 알림이 영구히 PENDING 으로 남는다
        inbound.claimPending(10, 30).size shouldBe 1
    }

    test("#66 임대는 상태를 바꾸지 않는다 — 실패한 건이 PENDING 으로 남는 성질을 지킨다") {
        // Given / When
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        record(roomTypeId, "R-7")
        inbound.claimPending(10, 30)

        // Then: 집기가 status 를 PROCESSING 같은 값으로 바꾸면, 처리 중 프로세스가
        // 죽었을 때 그 건이 어느 상태에도 속하지 않는다. 임대만 밀어 둔다
        jdbc.queryForObject(
            "SELECT status FROM inbound_message WHERE external_id = 'R-7'",
            String::class.java,
        ) shouldBe "PENDING"
    }

    test("#66 정렬은 유지된다 — 집기가 순서를 흔들지 않는다") {
        // Given: 같은 예약의 rev 2 와 rev 10
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        recorder.record(
            "CHANNEL_A",
            ReservationWebhook(
                event = WebhookEvent.RESERVATION_CANCELED,
                channelReservationId = "R-5",
                sequenceKey = "10",
            ),
        )
        recorder.record(
            "CHANNEL_A",
            ReservationWebhook(
                event = WebhookEvent.RESERVATION_CREATED,
                channelReservationId = "R-5",
                sequenceKey = "2",
                roomTypeId = roomTypeId,
                checkIn = march1,
                checkOut = march1.plusDays(1),
                roomCount = 1,
                guestName = "김손님",
            ),
        )

        // When
        val claimed = inbound.claimPending(10, 30)

        // Then: 집기 쿼리에 ORDER BY 가 빠지면 #72 가 되돌아간다.
        // rank 2(생성) 가 rank 10(취소) 보다 먼저 나와야 한다
        claimed.map { it.sequenceRank } shouldBe listOf(2L, 10L)
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
