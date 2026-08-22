package dev.preagile.stayinventory.webhook

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.inventory.InventoryFixture
import dev.preagile.stayinventory.persistence.ReservationRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.LocalDate
import javax.sql.DataSource

/**
 * 스케줄러가 **실제로 워커를 깨우는지** 본다.
 *
 * 나머지 웹훅 스펙은 `inbox.worker.enabled=false` 로 스케줄러를 끄고 워커를 직접
 * 부른다 -- 시점을 통제해야 "적기만 하고 처리는 안 했다" 를 확인할 수 있기 때문이다.
 * 그런데 **끄기만 하고 아무도 켜지 않으면 배선이 끊긴 것을 영영 모른다.**
 * `@Scheduled` 가 안 붙어 있거나 `@EnableScheduling` 이 빠져도 다른 테스트는
 * 전부 통과한다. 이 스펙 하나가 그것을 막는다.
 *
 * 스케줄러가 정합성을 담당하지 않는다는 것도 함께 드러난다 -- 이 스펙이 켜고
 * 나머지가 꺼도 결과가 같다.
 */
@SpringBootTest(properties = ["inbox.worker.enabled=true", "inbox.worker.delay-ms=200"])
@Import(PostgresTestContainer::class)
class InboundWorkerSchedulerTest(
    private val dataSource: DataSource,
    private val recorder: InboundMessageRecorder,
    private val reservations: ReservationRepository,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)
    val fixture = InventoryFixture(dataSource)

    beforeTest { fixture.wipe() }

    test("워커를 직접 부르지 않아도 스케줄러가 Inbox 를 소화한다") {
        // Given: 격자와, 적어 두기만 한 알림
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        recorder.record(
            "CHANNEL_A",
            ReservationWebhook(
                event = WebhookEvent.RESERVATION_CREATED,
                channelReservationId = "R-4001",
                sequenceKey = null,
                roomTypeId = roomTypeId,
                checkIn = march1,
                checkOut = march1.plusDays(1),
                roomCount = 1,
                guestName = "김손님",
            ),
        )

        // When / Then: 아무도 워커를 부르지 않는다. 스케줄러가 알아서 돌아야 한다
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            reservations.count() shouldBe 1
            fixture.sold(roomTypeId, march1) shouldBe 1
        }
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
