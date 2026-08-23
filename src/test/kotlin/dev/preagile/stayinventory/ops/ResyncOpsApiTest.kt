package dev.preagile.stayinventory.ops

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.channel.RecordingChannelAdapter
import dev.preagile.stayinventory.inventory.InventoryFixture
import dev.preagile.stayinventory.support.withOpsKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.LocalDate
import javax.sql.DataSource

/**
 * 재동기화 수동 트리거 (`#67`).
 *
 * 주기가 하루이므로 사고 직후 즉시 수렴시킬 경로가 없었다. diff 로 어긋남을 확인한
 * 운영자가 다음 주기를 기다리지 않고 돌릴 수 있어야 한다.
 *
 * **이 스펙이 지키는 것은 동시 클릭이 조용히 성공하지 않는 것이다.**
 * 두 운영자가 같이 누르거나 스케줄러와 겹쳤을 때 둘 다 200 을 받으면,
 * 운영자는 두 번 돌았다고 믿는데 실제로는 한 번 돌았다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestContainer::class)
class ResyncOpsApiTest(
    private val mockMvc: MockMvc,
    private val dataSource: DataSource,
    private val jdbc: JdbcTemplate,
    private val adapter: RecordingChannelAdapter,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)
    val fixture = InventoryFixture(dataSource)

    beforeTest {
        fixture.wipe()
        adapter.reset()
    }

    test("운영자가 누르면 그 자리에서 한 주기를 돈다") {
        // Given: 격자 3일
        fixture.seedGrid(march1, days = 3, physicalTotal = 10)

        // When
        mockMvc.perform(
            post("/ops/resync").withOpsKey()
                .param("from", march1.toString())
                .param("to", march1.plusDays(5).toString()),
        )
            // Then: 다음 주기를 기다리지 않고 나갔다
            .andExpect { it.response.status shouldBe 200 }

        adapter.snapshots shouldBe 3
    }

    test("임대가 잡혀 있으면 409 다 — 조용히 성공을 돌려주지 않는다") {
        // Given: 다른 인스턴스나 스케줄러가 돌고 있는 상태
        fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        jdbc.update(
            "UPDATE resync_cursor SET leased_until = now() + interval '10 minutes' WHERE id = 1",
        )

        // When / Then: 200 을 주면 운영자는 두 번 돌았다고 믿는데 한 번 돌았다.
        // 어느 쪽인지 알 수 없는 상태가 가장 나쁘다
        mockMvc.perform(post("/ops/resync").withOpsKey())
            .andExpect { it.response.status shouldBe 409 }

        adapter.snapshots shouldBe 0
    }

    test("구간을 주지 않으면 오늘부터 기본 지평까지 돈다") {
        // Given / When / Then: 파라미터 없이도 동작해야 한다 --
        // 사고 대응 중에 날짜를 계산하게 만들면 안 된다
        mockMvc.perform(post("/ops/resync").withOpsKey())
            .andExpect { it.response.status shouldBe 200 }
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
