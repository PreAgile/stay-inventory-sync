package dev.preagile.stayinventory.resync

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.channel.RecordingChannelAdapter
import dev.preagile.stayinventory.inventory.InventoryFixture
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import org.awaitility.Awaitility.await
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Duration
import java.time.LocalDate
import javax.sql.DataSource

/**
 * 재동기화 스케줄러가 **실제로 도는지** 본다.
 *
 * 나머지 재동기화 스펙은 `resync.enabled=false` 로 끄고 서비스를 직접 부른다.
 * **끄기만 하고 아무도 켜지 않으면 배선이 끊긴 것을 영영 모른다** -- 그리고
 * 이것은 최종 방어선이므로, 안 도는데 안 도는 줄 모르는 것이 가장 나쁘다.
 *
 * 오늘 날짜로 격자를 만든다. 스케줄러가 `LocalDate.now()` 부터 90일을 보므로
 * 고정 날짜(2026-03-01)로는 이 배선을 확인할 수 없다.
 */
@SpringBootTest(properties = ["resync.enabled=true", "resync.delay-ms=300"])
@Import(PostgresTestContainer::class)
class InventoryResyncSchedulerTest(
    private val dataSource: DataSource,
    private val adapter: RecordingChannelAdapter,
) : FunSpec({

    val fixture = InventoryFixture(dataSource)

    beforeTest {
        fixture.wipe()
        adapter.reset()
    }

    test("재동기화를 직접 부르지 않아도 스케줄러가 채널을 채운다") {
        // Given: 오늘부터 3일치 격자
        val today = LocalDate.now()
        val roomTypeId = fixture.seedGrid(today, days = 3, physicalTotal = 10)

        // When / Then: 아무도 resync() 를 부르지 않는다
        await().atMost(Duration.ofSeconds(10)).untilAsserted {
            adapter.currentInventory(roomTypeId, today, today.plusDays(3)).size shouldBeGreaterThanOrEqual 3
        }
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
