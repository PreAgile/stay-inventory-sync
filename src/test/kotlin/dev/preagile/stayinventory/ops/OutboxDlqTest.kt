package dev.preagile.stayinventory.ops

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.channel.ChannelSyncResult
import dev.preagile.stayinventory.channel.RecordingChannelAdapter
import dev.preagile.stayinventory.inventory.InventoryFixture
import dev.preagile.stayinventory.inventory.InventoryService
import dev.preagile.stayinventory.inventory.ReserveCommand
import dev.preagile.stayinventory.inventory.runConcurrentlyOrFail
import dev.preagile.stayinventory.outbox.relay.OutboxRelay
import dev.preagile.stayinventory.support.withOpsKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * **B2 — 영구 실패 통보의 종착지와 수동 재투입.**
 *
 * ## 이 슬라이스가 실제로 막는 것
 *
 * 이슈가 「주의」로 적어 둔 한 줄이 이 구현의 절반이다.
 *
 * > `RateLimited` 가 `DEAD` 로 떨어지면 안 된다. 레이트 리밋은 실패가 아니라
 * > **나중에 다시 하라는 신호**다.
 *
 * 그런데 `#4` 구현은 `RateLimited` 에서도 `retry_count` 를 올리고 있었다.
 * 소진 판정을 그 위에 그대로 얹으면 **리밋이 오래 걸린 채널의 정상 이벤트가
 * 다섯 번 만에 죽는다** -- 사장님이 예약을 많이 받았다는 이유로 통보가 죽는 것이다.
 *
 * 소진 판정은 *"몇 번 실패했는가"* 를 세는 것이지 *"몇 번 미뤘는가"* 가 아니다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestContainer::class)
class OutboxDlqTest(
    private val mockMvc: MockMvc,
    private val dataSource: DataSource,
    private val jdbc: JdbcTemplate,
    private val relay: OutboxRelay,
    private val adapter: RecordingChannelAdapter,
    private val inventoryService: InventoryService,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)
    val fixture = InventoryFixture(dataSource)

    beforeTest {
        fixture.wipe()
        adapter.reset()
    }

    fun reserve(roomTypeId: Long) {
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
    }

    fun statusOf(id: Long): String = requireNotNull(
        jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String::class.java, id),
    ) { "outbox_event $id 이 없다" }

    fun retryCountOf(id: Long): Int = requireNotNull(
        jdbc.queryForObject("SELECT retry_count FROM outbox_event WHERE id = ?", Int::class.java, id),
    ) { "outbox_event $id 이 없다" }

    fun singleEventId(): Long = requireNotNull(
        jdbc.queryForObject("SELECT id FROM outbox_event LIMIT 1", Long::class.java),
    ) { "통보가 하나도 만들어지지 않았다" }

    /**
     * 실패를 [times] 번 반복하고 **끝난 시각을 돌려준다.**
     *
     * 시각을 돌려주지 않으면 이어지는 호출이 `Instant.now()` 로 다시 시작해,
     * 앞선 백오프가 밀어 둔 `next_attempt_at` 보다 **이전 시각**으로 폴링하게 된다.
     * 그러면 아무것도 안 잡히는데 테스트는 "실패를 겪었다" 고 믿는다.
     */
    fun failTimes(times: Int, result: ChannelSyncResult, from: Instant = Instant.now()): Instant {
        adapter.forcedResult = result
        var now = from
        repeat(times) {
            relay.drain(now = now)
            now = now.plus(Duration.ofHours(1))
        }
        return now
    }

    // ── 소진 ──────────────────────────────────────────────────────────────
    test("일시 실패가 소진되면 DEAD 로 간다") {
        // Given: 통보 하나가 계속 5xx 를 받는다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        val eventId = singleEventId()

        // When: 소진 한계를 넘긴다
        failTimes(OutboxRelay.MAX_RETRIES + 1, ChannelSyncResult.Retryable("502"))

        // Then: 무한 재시도를 두면 그 이벤트가 영원히 큐에 남아 큐 길이가
        // 지표로서 의미를 잃는다
        statusOf(eventId) shouldBe "DEAD"

        // 마지막 실패도 세어져 있어야 한다. status 만 바꾸면 "retry_count > 5 면
        // DEAD" 라는 계약과 목록이 보여 주는 숫자가 어긋나고, 운영자가
        // "아직 여유가 있는데 왜 죽었나" 를 묻게 된다
        retryCountOf(eventId) shouldBe OutboxRelay.MAX_RETRIES + 1
    }

    test("영구 실패는 재시도 예산을 쓰지 않는다 — 한 번의 응답으로 판정한 것이다") {
        // Given: 4xx 를 주는 채널
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        val eventId = singleEventId()

        // When
        adapter.forcedResult = ChannelSyncResult.Permanent("400 잘못된 룸타입")
        relay.drain()

        // Then: 소진과 영구 실패는 다른 사건이다. 함께 올리면 목록에서
        // "다섯 번 실패했다" 로 읽혀 원인 추적이 어긋난다
        statusOf(eventId) shouldBe "DEAD"
        retryCountOf(eventId) shouldBe 0
    }

    test("소진 직전까지는 살아 있다 — 한 번 실패했다고 죽지 않는다") {
        // Given: 통보 하나
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        val eventId = singleEventId()

        // When: 소진 한계까지만 실패한다
        failTimes(OutboxRelay.MAX_RETRIES, ChannelSyncResult.Retryable("502"))

        // Then
        statusOf(eventId) shouldBe "PENDING"
        retryCountOf(eventId) shouldBe OutboxRelay.MAX_RETRIES
    }

    test("레이트 리밋은 아무리 반복돼도 DEAD 로 가지 않는다") {
        // Given: 채널이 계속 429 를 준다. 성수기에 흔한 상황이다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        val eventId = singleEventId()

        // When: 소진 한계의 네 배를 받아 본다
        failTimes(OutboxRelay.MAX_RETRIES * 4, ChannelSyncResult.RateLimited(null))

        // Then: 429 는 실패가 아니다. 여기서 죽으면 **정상 채널의 정상 이벤트가**
        // 사장님이 예약을 많이 받았다는 이유로 사라진다
        statusOf(eventId) shouldBe "PENDING"
        retryCountOf(eventId) shouldBe 0
    }

    test("리밋과 실패가 섞여도 실패만 센다") {
        // Given: 429 를 여러 번 받은 뒤 5xx 가 한 번 온다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        val eventId = singleEventId()

        val afterLimits = failTimes(10, ChannelSyncResult.RateLimited(null))
        failTimes(1, ChannelSyncResult.Retryable("502"), from = afterLimits)

        // Then: 리밋 10회가 예산을 갉아먹었다면 여기서 이미 죽었을 것이다
        statusOf(eventId) shouldBe "PENDING"
        retryCountOf(eventId) shouldBe 1
    }

    // ── 운영 API ──────────────────────────────────────────────────────────
    test("영구 실패 목록에 payload 가 함께 나온다") {
        // Given: DEAD 이벤트 하나
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        adapter.forcedResult = ChannelSyncResult.Permanent("400 잘못된 룸타입")
        relay.drain()

        // When
        val response = mockMvc.perform(get("/ops/outbox/dead").withOpsKey()).andReturn().response

        // Then: 무엇이 안 나갔는지 모르면 되살릴지 판단할 수 없다.
        // 재고 통보라면 지금 값과 비교해야 하고, 이미 더 새로운 값이 나갔다면
        // 되살릴 이유가 없다
        response.status shouldBe 200
        response.contentAsString shouldContain "INVENTORY_CHANGED"
        response.contentAsString shouldContain "remaining"
        response.contentAsString shouldContain "\"version\":1"
    }

    test("수동 재투입은 상태와 예산을 함께 되돌린다") {
        // Given: 소진으로 죽은 이벤트
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        val eventId = singleEventId()
        failTimes(OutboxRelay.MAX_RETRIES + 1, ChannelSyncResult.Retryable("502"))
        statusOf(eventId) shouldBe "DEAD"

        // When: 운영자가 원인을 고치고 재투입한다
        val response = mockMvc.perform(post("/ops/outbox/$eventId/retry").withOpsKey()).andReturn().response

        // Then: retry_count 를 되돌리지 않으면 다음 실패 한 번에 다시 죽어
        // 재투입이 사실상 아무 일도 하지 않는다
        response.status shouldBe 200
        statusOf(eventId) shouldBe "PENDING"
        retryCountOf(eventId) shouldBe 0

        // 되살아난 이벤트는 실제로 다시 발행된다
        adapter.reset()
        relay.drain().published shouldBe 1
    }

    test("DEAD 가 아닌 것은 재투입되지 않는다") {
        // Given: 아직 발행 대기 중인 이벤트
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        val eventId = singleEventId()

        // When
        val response = mockMvc.perform(post("/ops/outbox/$eventId/retry").withOpsKey()).andReturn().response

        // Then: PENDING 을 되살리면 백오프가 초기화되어 장애 중인 채널을 더 세게
        // 때린다. PUBLISHED 를 되살리면 이미 나간 통보가, 그것도 낡은 값으로 다시 나간다
        response.status shouldBe 409
        statusOf(eventId) shouldBe "PENDING"
    }

    test("이미 발행된 이벤트도 재투입되지 않는다") {
        // Given
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        val eventId = singleEventId()
        relay.drain()
        statusOf(eventId) shouldBe "PUBLISHED"

        // When: 되살리려 한다
        val response = mockMvc.perform(post("/ops/outbox/$eventId/retry").withOpsKey()).andReturn().response

        // Then: 이미 나간 통보가 낡은 값으로 다시 나가는 것을 막는다
        response.status shouldBe 409
    }

    test("두 운영자가 동시에 눌러도 한 번만 재투입된다") {
        // Given: DEAD 이벤트
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        val eventId = singleEventId()
        failTimes(OutboxRelay.MAX_RETRIES + 1, ChannelSyncResult.Retryable("502"))

        // When: 두 요청을 **실제로 동시에** 출발시킨다.
        //
        // 순차로 두 번 부르면 "이미 옮겨진 것을 또 옮기지 않는다" 만 증명된다.
        // 그것은 조건부 UPDATE 가 아니라 상태 비교로도 통과하는 성질이고,
        // 이 테스트가 지키려는 것은 **읽기와 쓰기 사이가 열리지 않는다**는 쪽이다
        val statuses = java.util.concurrent.ConcurrentLinkedQueue<Int>()
        runConcurrentlyOrFail(2) {
            statuses += mockMvc.perform(post("/ops/outbox/$eventId/retry").withOpsKey())
                .andReturn().response.status
        }

        // Then: 정확히 하나만 성공한다
        statuses.count { it == 200 } shouldBe 1
        statuses.count { it == 409 } shouldBe 1

        // 그리고 재투입은 한 번만 일어났다 -- 둘 다 통과했다면 예산이 두 번
        // 초기화될 뿐 결과가 같아 보이므로, 상태와 예산을 함께 확인한다
        statusOf(eventId) shouldBe "PENDING"
        retryCountOf(eventId) shouldBe 0
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
