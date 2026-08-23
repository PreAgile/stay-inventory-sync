package dev.preagile.stayinventory.ops

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.channel.ChannelSyncResult
import dev.preagile.stayinventory.channel.RecordingChannelAdapter
import dev.preagile.stayinventory.inventory.InventoryFixture
import dev.preagile.stayinventory.inventory.InventoryService
import dev.preagile.stayinventory.inventory.ReserveCommand
import dev.preagile.stayinventory.inventory.ReserveResult
import dev.preagile.stayinventory.outbox.relay.OutboxRelay
import dev.preagile.stayinventory.support.withOpsKey
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
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
 * **B5 — 지표 2종.**
 *
 * ```
 * overbooking_prevented_total   재고 부족으로 거절된 요청 수
 * outbox_publish_lag_seconds    created_at 부터 published_at 까지의 분포
 * ```
 *
 * ## 이 스펙이 지키는 것은 "값이 나온다" 가 아니라 **"거짓말하지 않는다"** 다
 *
 * 지표는 틀려도 초록불이다. 아무도 예외를 보지 못하고, 틀린 값을 근거로 결정이
 * 내려질 뿐이다. 그래서 여기서는 **세면 안 되는 것을 세지 않는지**를 주로 본다.
 *
 * | 세지 않아야 하는 것 | 세면 |
 * |---|---|
 * | 격자 없음(`NO_GRID`) 거절 | 설정 실수가 방어 실적으로 보고된다 |
 * | `SUPERSEDED` 이벤트 | 나간 적 없는 것이 "0초 지연" 으로 잡혀 분포가 좋아 보인다 |
 * | 성공한 예약 | 말할 것도 없다 |
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestContainer::class)
class MetricsTest(
    private val mockMvc: MockMvc,
    private val dataSource: DataSource,
    private val jdbc: JdbcTemplate,
    private val relay: OutboxRelay,
    private val adapter: RecordingChannelAdapter,
    private val inventoryService: InventoryService,
    private val counter: OverbookingPreventedCounter,
    private val metrics: MetricsController,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)
    val fixture = InventoryFixture(dataSource)

    beforeTest {
        fixture.wipe()
        adapter.reset()
    }

    fun reserve(roomTypeId: Long, checkIn: LocalDate = march1, nights: Long = 1): ReserveResult =
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

    // ── 오버부킹 차단 ─────────────────────────────────────────────────────
    test("매진으로 거절될 때마다 차단 건수가 오른다") {
        // Given: 물리 1 인 날짜
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 1)
        val before = counter.total

        // When: 한 건 성공, 세 건 거절
        reserve(roomTypeId).shouldBeInstanceOf<ReserveResult.Reserved>()
        repeat(3) { reserve(roomTypeId).shouldBeInstanceOf<ReserveResult.Rejected>() }

        // Then: 오버부킹이 일어나지 않았다는 것은 관측되지 않는다.
        // 이 숫자가 그 주장을 운영에서 확인하는 유일한 경로다
        counter.total shouldBe before + 3
    }

    test("성공한 예약은 세지 않는다") {
        // Given / When
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        val before = counter.total
        repeat(3) { reserve(roomTypeId).shouldBeInstanceOf<ReserveResult.Reserved>() }

        // Then
        counter.total shouldBe before
    }

    test("격자 없음 거절은 세지 않는다 — 설정 실수가 방어 실적이 되면 안 된다") {
        // Given: 격자를 하루만 열어 두고 2박을 요청한다
        val roomTypeId = fixture.seedGrid(march1, days = 1, physicalTotal = 10)
        val before = counter.total

        // When
        reserve(roomTypeId, nights = 2).shouldBeInstanceOf<ReserveResult.Rejected>()

        // Then: 이것은 "막았다" 가 아니라 "재고를 열지 않았다" 는 운영 신호다.
        // 함께 세면 재고를 안 연 것이 오버부킹 방어 실적으로 보고된다
        counter.total shouldBe before
    }

    test("차단 건수에는 기준 시점이 함께 나온다 — 인메모리라는 사실을 숨기지 않는다") {
        // Given / When
        val snapshot = metrics.metrics()

        // Then: 재기동하면 0 으로 돌아가는 값이다. since 없이 읽으면
        // "지금까지 총 N 건" 으로 오해한다
        snapshot.overbookingPrevented.since.isBefore(java.time.Instant.now()) shouldBe true
    }

    // ── 발행 지연 ─────────────────────────────────────────────────────────
    test("발행된 통보만 지연 분포에 들어간다") {
        // Given: 통보 3건 중 2건만 발행한다
        val roomTypeId = fixture.seedGrid(march1, days = 4, physicalTotal = 10)
        reserve(roomTypeId, nights = 3)
        relay.drain(limit = 2)

        // When
        val lag = metrics.metrics().outboxPublishLag

        // Then: 아직 안 나간 것은 지연을 말할 수 없다
        lag.samples shouldBe 2
    }

    test("건너뛴 통보는 지연 분포에 들어가지 않는다 — 분포가 좋아 보이면 안 된다") {
        // Given: 같은 키에 통보 둘. 하나는 SUPERSEDED 가 된다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        val first = reserve(roomTypeId).shouldBeInstanceOf<ReserveResult.Reserved>()
        inventoryService.cancel(first.reservationId)
        relay.drain()

        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE status = 'SUPERSEDED'", Int::class.java,
        ) shouldBe 1

        // When
        val lag = metrics.metrics().outboxPublishLag

        // Then: 나간 적이 없으므로 지연이라는 개념이 없다.
        // 세면 "0초에 처리됨" 으로 잡혀 분포가 실제보다 좋아 보인다
        lag.samples shouldBe 1
    }

    test("지연이 실제로 벌어지면 분포에 나타난다") {
        // Given: 통보 하나가 오래 전에 만들어진 것으로 만든다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        jdbc.update("UPDATE outbox_event SET created_at = now() - INTERVAL '10 minutes'")

        // When
        relay.drain()
        val lag = metrics.metrics().outboxPublishLag

        // Then: 평균을 내지 않는 이유가 이것이다. 재고 통보의 지연은 꼬리가
        // 문제이고, 오버부킹은 그 꼬리에서 나온다
        lag.maxSeconds shouldBeGreaterThan 590.0
        lag.p95Seconds shouldBeGreaterThan 590.0
    }

    test("표본이 없으면 0 을 준다 — null 이나 예외가 아니다") {
        // Given: 아무 통보도 없다
        // When
        val lag = metrics.metrics().outboxPublishLag

        // Then: 지표 엔드포인트가 빈 DB 에서 죽으면, 정작 문제가 있을 때
        // 아무것도 못 본다
        lag.samples shouldBe 0
        lag.p50Seconds shouldBe 0.0
    }

    // ── 밀린 것 ───────────────────────────────────────────────────────────
    test("지연 분포만으로는 밀린 것이 보이지 않는다 — 대기 건수를 함께 낸다") {
        // Given: 하나는 나가고 둘은 계속 실패해 밀린다
        val roomTypeId = fixture.seedGrid(march1, days = 4, physicalTotal = 10)
        reserve(roomTypeId, nights = 3)
        relay.drain(limit = 1)
        adapter.forcedResult = ChannelSyncResult.Retryable("502")
        relay.drain()

        // When
        val snapshot = metrics.metrics()

        // Then: 지연 분포는 **이미 나간 것**만 본다. 영원히 안 나가는 이벤트가
        // 쌓이면 밀린 것이 표본에서 빠져 분포가 오히려 좋아 보인다
        snapshot.outboxPublishLag.samples shouldBe 1
        snapshot.outboxBacklog.pending shouldBe 2
        snapshot.outboxBacklog.oldestPendingAgeSeconds shouldBeGreaterThan 0.0
    }

    test("영구 실패와 건너뛴 것을 대기 건수와 구분해서 낸다") {
        // Given: 하나는 DEAD
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        adapter.forcedResult = ChannelSyncResult.Permanent("400")
        relay.drain()

        // When
        val backlog = metrics.metrics().outboxBacklog

        // Then: DEAD 를 대기로 세면 "밀려 있다" 로 읽혀 사람을 잘못된 곳으로 보낸다.
        // DEAD 는 밀린 것이 아니라 포기한 것이고, 대응이 다르다
        backlog.pending shouldBe 0
        backlog.dead shouldBe 1
    }

    // ── 엔드포인트 ────────────────────────────────────────────────────────
    test("운영 엔드포인트가 세 지표를 한 번에 준다") {
        // Given
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 1)
        reserve(roomTypeId)
        reserve(roomTypeId)
        relay.drain()

        // When
        val response = mockMvc.perform(get("/ops/metrics").withOpsKey()).andReturn().response

        // Then: 나눠 두면 사람이 두 번 봐야 하고, 두 번 보면 한 번은 안 본다
        response.status shouldBe 200
        response.contentAsString shouldContain "overbookingPrevented"
        response.contentAsString shouldContain "outboxPublishLag"
        response.contentAsString shouldContain "outboxBacklog"
        response.contentAsString shouldContain "since"

        counter.total shouldBeGreaterThanOrEqual 1L
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
