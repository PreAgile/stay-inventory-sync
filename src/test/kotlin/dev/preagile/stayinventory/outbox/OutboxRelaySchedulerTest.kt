package dev.preagile.stayinventory.outbox

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.channel.RecordingChannelAdapter
import dev.preagile.stayinventory.inventory.InventoryFixture
import dev.preagile.stayinventory.inventory.InventoryService
import dev.preagile.stayinventory.inventory.ReserveCommand
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.awaitility.Awaitility.await
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * 릴레이 스케줄러가 **실제로 도는지** 본다.
 *
 * 나머지 Outbox 스펙은 스케줄러를 끄고 `drain()` 을 직접 부른다 -- `T4` 가
 * "마킹 직전 사망" 을 재현하는 사이에 스케줄러가 끼어들면 그 창을 만들 수 없다.
 *
 * **끄기만 하고 아무도 켜지 않으면 배선이 끊긴 것을 영영 모른다.** `@Scheduled`
 * 가 빠져도 다른 테스트는 전부 통과한다. 이 스펙 하나가 그것을 막는다.
 */
@SpringBootTest(properties = ["outbox.relay.enabled=true", "outbox.relay.delay-ms=200"])
@Import(PostgresTestContainer::class)
class OutboxRelaySchedulerTest(
    private val dataSource: DataSource,
    private val inventoryService: InventoryService,
    private val adapter: RecordingChannelAdapter,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)
    val fixture = InventoryFixture(dataSource)

    beforeTest {
        fixture.wipe()
        adapter.reset()
    }

    test("릴레이를 직접 부르지 않아도 스케줄러가 통보를 내보낸다") {
        // Given: 예약 하나 -> 통보 하나
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        inventoryService.reserve(
            ReserveCommand(
                roomTypeId = roomTypeId,
                checkIn = march1,
                checkOut = march1.plusDays(1),
                roomCount = 1,
                channel = "CHANNEL_A",
                channelReservationId = UUID.randomUUID().toString(),
                guestName = "김손님",
            ),
        )

        // When / Then: 아무도 drain() 을 부르지 않는다
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            adapter.applied shouldBe 1
        }
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
