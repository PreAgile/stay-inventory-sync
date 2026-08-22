package dev.preagile.stayinventory.ops

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.inventory.InventoryFixture
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import java.time.LocalDate
import javax.sql.DataSource

/**
 * 캡 운영 API. **화면은 범위 밖이다** — 값이 시스템에 어떻게 들어와 어떻게
 * 투영되는지까지가 이 저장소의 범위다 (`ADR-0007` 이 오버부킹 한도에 쓴 경계와 같다).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestContainer::class)
class ChannelPolicyApiTest(
    private val mockMvc: MockMvc,
    private val dataSource: DataSource,
    private val jdbc: JdbcTemplate,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)
    val fixture = InventoryFixture(dataSource)

    beforeTest { fixture.wipe() }

    fun propertyId(): Long = requireNotNull(
        jdbc.queryForObject("SELECT max(id) FROM property", Long::class.java),
    ) { "숙소가 없다" }

    fun setCap(roomTypeId: Long, value: Int, channel: String = "CHANNEL_C") =
        mockMvc.perform(
            put("/ops/channel-policy").contentType(MediaType.APPLICATION_JSON).content(
                """
                {"roomTypeId": $roomTypeId, "stayDate": "$march1",
                 "channel": "$channel", "value": $value}
                """.trimIndent(),
            ),
        ).andReturn().response

    test("캡을 걸면 200 이고 장부에 남는다") {
        // Given
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)

        // When
        val response = setCap(roomTypeId, 5)

        // Then
        response.status shouldBe 200
        jdbc.queryForObject("SELECT value FROM channel_policy", Int::class.java) shouldBe 5
    }

    test("같은 캡을 다시 걸어도 200 이다 — 멱등이다") {
        // Given
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        setCap(roomTypeId, 5)

        // When
        val response = setCap(roomTypeId, 5)

        // Then: 운영 API 가 재시도에 4xx 를 주면 자동화 스크립트가 멈춘다
        response.status shouldBe 200
        jdbc.queryForObject("SELECT count(*) FROM channel_policy", Int::class.java) shouldBe 1
    }

    test("음수 캡은 400 이다") {
        // Given
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)

        // When / Then: 0 은 유효하고 음수는 아니다. DB CHECK 가 최종 방어선이지만
        // 400 과 500 을 가르려면 여기서 먼저 걸러야 한다
        setCap(roomTypeId, -1).status shouldBe 400
    }

    test("캡 해제는 200, 없던 것을 지우면 404 다") {
        // Given: 캡이 하나 걸려 있다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        setCap(roomTypeId, 5)

        fun remove(channel: String) = mockMvc.perform(
            delete("/ops/channel-policy")
                .param("roomTypeId", roomTypeId.toString())
                .param("stayDate", march1.toString())
                .param("channel", channel),
        ).andReturn().response

        // When / Then
        remove("CHANNEL_C").status shouldBe 200

        // 없던 것을 지우는 요청에 200 을 주면 운영자가 "해제했다" 고 믿는데
        // 실제로는 다른 날짜나 다른 채널에 건 것을 지우려 했을 가능성이 크다
        remove("CHANNEL_C").status shouldBe 404
        remove("CHANNEL_B").status shouldBe 404
    }

    test("목록은 숙소와 기간으로 좁혀 준다") {
        // Given: 두 날짜에 캡
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        setCap(roomTypeId, 5)
        mockMvc.perform(
            put("/ops/channel-policy").contentType(MediaType.APPLICATION_JSON).content(
                """
                {"roomTypeId": $roomTypeId, "stayDate": "${march1.plusDays(2)}",
                 "channel": "CHANNEL_C", "value": 2}
                """.trimIndent(),
            ),
        )

        // When: 첫날만 조회한다
        val response = mockMvc.perform(
            get("/ops/channel-policy")
                .param("propertyId", propertyId().toString())
                .param("from", march1.toString())
                .param("to", march1.plusDays(1).toString()),
        ).andReturn().response

        // Then: 반개구간이다
        response.status shouldBe 200
        response.contentAsString shouldContain "\"value\":5"
        response.contentAsString.contains("\"value\":2") shouldBe false
        response.contentAsString shouldContain "\"source\":\"OURS\""
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
