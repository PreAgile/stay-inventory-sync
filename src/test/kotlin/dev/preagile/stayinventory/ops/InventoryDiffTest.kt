package dev.preagile.stayinventory.ops

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.channel.RecordingChannelAdapter
import dev.preagile.stayinventory.inventory.InventoryFixture
import dev.preagile.stayinventory.inventory.InventoryService
import dev.preagile.stayinventory.inventory.ReserveCommand
import dev.preagile.stayinventory.outbox.relay.OutboxRelay
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * **B1 — 채널 재고 diff 리포트.**
 *
 * ## 이 스펙이 증명하는 것
 *
 * *"감지 기능을 만들었다"* 보다 **"감지가 실제로 작동함을 증명했다"** 가 강하다.
 * 그래서 어댑터가 재고 반영을 **조용히 누락**하도록 만들고 — 실패도 예외도 없이
 * 값만 안 바뀌게 하고 — 리포트가 그것을 잡아내는지 본다.
 *
 * 조용한 누락이 실제 연동에서 가장 흔한 어긋남이다. 실패는 로그에 남지만
 * **조용한 누락은 아무 데도 안 남는다.**
 *
 * ## 평소에 비어 있어야 한다
 *
 * 값이 같은 격자는 리포트에 넣지 않는다. 전부 나열하면 사람이 다시 눈으로 훑게
 * 되고, **그건 확인 업무의 부활이다** — 이 항목이 Tier 1 인 이유가 거기 있다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestContainer::class)
class InventoryDiffTest(
    private val mockMvc: MockMvc,
    private val dataSource: DataSource,
    private val jdbc: JdbcTemplate,
    private val relay: OutboxRelay,
    private val adapter: RecordingChannelAdapter,
    private val inventoryService: InventoryService,
    private val diffController: InventoryDiffController,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)
    val fixture = InventoryFixture(dataSource)

    beforeTest {
        fixture.wipe()
        adapter.reset()
    }

    fun propertyId(): Long = requireNotNull(
        jdbc.queryForObject("SELECT max(id) FROM property", Long::class.java),
    ) { "숙소가 없다" }

    fun reserve(roomTypeId: Long, checkIn: LocalDate = march1, nights: Long = 1) {
        inventoryService.reserve(
            ReserveCommand(
                roomTypeId = roomTypeId,
                checkIn = checkIn,
                checkOut = checkIn.plusDays(nights),
                roomCount = 1,
                channel = "CHANNEL_A",
                channelReservationId = UUID.randomUUID().toString(),
                guestName = "김손님",
            ),
        )
    }

    fun diff(from: LocalDate = march1, to: LocalDate = march1.plusDays(5)) =
        diffController.diff(propertyId(), from, to)

    /**
     * 그 날짜 하나만 본다.
     *
     * 격자를 열어 두기만 하고 아직 아무 통보도 나가지 않은 날짜는 **정당하게**
     * 어긋남으로 잡힌다 -- 채널은 그 날짜의 존재를 모른다. 그래서 전체 건수로
     * 단언하지 않고 관심 있는 날짜를 집어 본다.
     */
    fun diffOn(date: LocalDate) = diff().filter { it.stayDate == date }

    // ── 정상 상태 ─────────────────────────────────────────────────────────
    test("통보가 전부 나갔으면 리포트는 비어 있다") {
        // Given: 3일 격자에 예약 하나, 통보가 모두 발행됐다
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        reserve(roomTypeId, nights = 3)
        relay.drain()

        // Then: 평소에 비어 있어야 뭔가 떴을 때 그것이 신호로 읽힌다
        diff() shouldHaveSize 0
    }

    // ── 조용한 누락 ───────────────────────────────────────────────────────
    test("어댑터가 조용히 누락한 날짜를 잡아낸다 — 실패도 예외도 없었다") {
        // Given: 3/2 만 반영이 누락되도록 만든다. 응답은 여전히 Success 다
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        adapter.dropSilently(roomTypeId, march1.plusDays(1))
        reserve(roomTypeId, nights = 3)

        // When: 릴레이는 전부 성공으로 처리한다
        val report = relay.drain()
        report.published shouldBe 3
        report.dead shouldBe 0

        // Then: 실패는 로그에 남지만 조용한 누락은 아무 데도 안 남는다.
        // Outbox 상태만 보면 전부 PUBLISHED 이고 아무 문제가 없어 보인다
        val diffs = diff()
        diffs shouldHaveSize 1
        diffs.single().stayDate shouldBe march1.plusDays(1)
        diffs.single().internal shouldBe 9
        // 채널은 그 날짜를 아예 모른다 -- 0 이 아니라 null 이다
        diffs.single().channelValue shouldBe null
        diffs.single().delta shouldBe null
    }

    test("채널이 다른 값을 들고 있으면 delta 가 나온다") {
        // Given: 통보를 한 번 내보낸 뒤(잔여 9), 그 다음 예약의 통보를 누락시킨다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        relay.drain()
        adapter.dropSilently(roomTypeId, march1)
        reserve(roomTypeId)
        relay.drain()

        // Then: 채널은 9 를 알고 우리는 8 이다. delta 가 +1 이라는 것은
        // **채널이 실제보다 방이 많다고 믿는다**는 뜻이고, 그 방향이 오버부킹이다
        val diffs = diffOn(march1)
        diffs shouldHaveSize 1
        diffs.single().internal shouldBe 8
        diffs.single().channelValue shouldBe 9
        diffs.single().delta shouldBe 1
    }

    test("아직 발행되지 않은 통보가 있으면 그 날짜가 어긋난 것으로 나온다") {
        // Given: 예약은 됐는데 릴레이가 아직 안 돌았다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)

        // Then: 이것은 결함이 아니라 **정상적인 전파 지연**이다. 리포트는
        // 그것을 구분하지 못한다 -- 구분하려면 발행 지연 지표(#10)와 함께 봐야 한다.
        // 리포트가 "무엇이 다른가" 만 말하고 "왜" 를 말하지 않는 이유이며,
        // 그래서 자동 보정을 붙이지 않았다
        diffOn(march1) shouldHaveSize 1
    }

    test("한 번도 통보된 적 없는 날짜도 어긋남으로 잡힌다 — 초기 동기화가 빠진 것이다") {
        // Given: 격자만 열어 두고 아무 예약도 통보도 없다
        fixture.seedGrid(march1, days = 3, physicalTotal = 10)

        // When
        val diffs = diff()

        // Then: 채널은 그 날짜의 **존재를 모른다.** 손님이 그 날짜를 아예 볼 수
        // 없으므로 오버부킹은 안 나지만 **팔 수 있는 방을 못 판다.**
        //
        // 이것은 결함이 아니라 "격자를 열면 채널에 알려야 한다" 는 요구가
        // 아직 구현되지 않았다는 신호다. 리포트가 그것을 드러낸다
        diffs shouldHaveSize 3
        diffs.all { it.channelValue == null } shouldBe true
    }

    // ── 범위 ──────────────────────────────────────────────────────────────
    test("조회 범위 밖의 날짜는 보지 않는다") {
        // Given: 3/1 과 3/4 두 날짜가 어긋나 있다
        val roomTypeId = fixture.seedGrid(march1, days = 5, physicalTotal = 10)
        reserve(roomTypeId, checkIn = march1)
        reserve(roomTypeId, checkIn = march1.plusDays(3))

        // When: 3/1 ~ 3/2 만 본다
        val diffs = diff(from = march1, to = march1.plusDays(1))

        // Then: 반개구간이다. to 당일은 포함하지 않는다
        diffs shouldHaveSize 1
        diffs.single().stayDate shouldBe march1
    }

    test("범위가 뒤집혀 있으면 거부한다") {
        // Given / When / Then: 조용히 빈 결과를 주면 "어긋남이 없다" 로 읽힌다.
        // 지표와 리포트에서 빈 결과와 잘못된 질문은 반드시 구분돼야 한다
        val thrown = runCatching {
            diff(from = march1.plusDays(3), to = march1)
        }.exceptionOrNull()

        (thrown is IllegalArgumentException) shouldBe true
    }

    test("다른 숙소의 격자는 섞이지 않는다") {
        // Given: 숙소 둘. 각각 어긋남이 하나씩 있다
        val roomTypeA = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeA)
        val firstProperty = propertyId()
        val roomTypeB = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeB)

        // When
        val diffs = diffController.diff(firstProperty, march1, march1.plusDays(2))

        // Then: 숙소 경계를 넘어 보고하면 다른 지점 담당자가 볼 필요 없는 것을 본다.
        // 격자 2일치가 모두 미통보이므로 두 건이고, 중요한 것은 **B 가 없다**는 것이다
        diffs shouldHaveSize 2
        diffs.map { it.roomTypeId }.distinct() shouldBe listOf(roomTypeA)
    }

    // ── 엔드포인트 ────────────────────────────────────────────────────────
    test("운영 엔드포인트가 어긋남 목록을 준다") {
        // Given
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        adapter.dropSilently(roomTypeId, march1)
        reserve(roomTypeId)
        relay.drain()

        // When
        val response = mockMvc.perform(
            get("/ops/inventory-diff")
                .param("propertyId", propertyId().toString())
                .param("from", march1.toString())
                .param("to", march1.plusDays(2).toString()),
        ).andReturn().response

        // Then
        response.status shouldBe 200
        response.contentAsString shouldContain "\"internal\":9"
        response.contentAsString shouldContain "\"channelValue\":null"
    }

    test("자동 보정을 하지 않는다 — 리포트를 봐도 재고가 그대로다") {
        // Given: 어긋난 상태
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        adapter.dropSilently(roomTypeId, march1)
        reserve(roomTypeId)
        relay.drain()
        val soldBefore = fixture.sold(roomTypeId, march1)

        // When: 리포트를 여러 번 본다
        repeat(3) { diffOn(march1) shouldHaveSize 1 }

        // Then: 어긋남의 원인은 셋이고 대응이 각각 다르다. 우리 계산이 틀린
        // 경우에 재발행하면 틀린 값을 더 확실히 밀어넣는다 -- 원인을 모르는
        // 자동 보정은 그 경우에 상황을 악화시킨다
        fixture.sold(roomTypeId, march1) shouldBe soldBefore
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE status = 'PENDING'", Int::class.java,
        ) shouldBe 0
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
