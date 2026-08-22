package dev.preagile.stayinventory.outbox

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.channel.ChannelSyncResult
import dev.preagile.stayinventory.channel.RecordingChannelAdapter
import dev.preagile.stayinventory.inventory.InventoryFixture
import dev.preagile.stayinventory.inventory.InventoryService
import dev.preagile.stayinventory.inventory.ReserveCommand
import dev.preagile.stayinventory.inventory.ReserveResult
import dev.preagile.stayinventory.outbox.relay.OutboxRelay
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * **T4 — Outbox 재발행이 부작용을 중복시키지 않는다.**
 *
 * ```
 * Given  발행 후 published 마킹 직전에 릴레이가 죽은 상황
 * When   릴레이 재기동 후 동일 이벤트를 다시 발행
 * Then   ChannelAdapter 의 실질 부작용은 1회
 * ```
 *
 * **정확히 한 번은 발행 측이 아니라 수신 측에서 만들어진다** (ADR-0003).
 * 릴레이는 at-least-once 만 보장하고, 중복을 흡수하는 것은 멱등키다.
 *
 * ## "죽음" 을 어떻게 재현하는가
 *
 * 테스트용 훅을 넣지 않았다. 릴레이가 **발행과 마킹을 별도 메서드로** 갖고 있으므로,
 * 테스트가 발행만 부르고 마킹을 부르지 않으면 그것이 곧 그 창이다.
 * 훅을 넣으면 프로덕션 코드에 테스트 전용 분기가 생기고, 그 분기가 언젠가
 * 운영에서 켜진다.
 *
 * ## `attempts` 와 `applied` 를 나눠 세는 이유
 *
 * at-least-once 는 **호출이 여러 번 나가는 것을 막지 않는다.** 막는 것은
 * 부작용이 여러 번 나는 것이다. 두 숫자를 하나로 세면 그 구분이 사라지고,
 * "중복 호출 0" 이라는 틀린 목표를 증명하려 들게 된다.
 */
@SpringBootTest
@Import(PostgresTestContainer::class)
class OutboxRelayTest(
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

    fun reserve(roomTypeId: Long, nights: Long = 1): Long =
        inventoryService.reserve(
            ReserveCommand(
                roomTypeId = roomTypeId,
                checkIn = march1,
                checkOut = march1.plusDays(nights),
                roomCount = 1,
                channel = "CHANNEL_A",
                channelReservationId = UUID.randomUUID().toString(),
                guestName = "김손님",
            ),
        ).shouldBeInstanceOf<ReserveResult.Reserved>().reservationId

    fun statusOf(id: Long): String =
        jdbc.queryForObject("SELECT status FROM outbox_event WHERE id = ?", String::class.java, id)!!

    fun pendingIds(): List<Long> =
        jdbc.queryForList("SELECT id FROM outbox_event WHERE status = 'PENDING' ORDER BY id", Long::class.java)

    // ── 정상 발행 ─────────────────────────────────────────────────────────
    test("커밋된 통보를 릴레이가 발행하고 PUBLISHED 로 표시한다") {
        // Given: 2박 예약 -> 통보 2건
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        reserve(roomTypeId, nights = 2)
        pendingIds() shouldHaveSize 2

        // When
        val report = relay.drain()

        // Then
        report.published shouldBe 2
        adapter.applied shouldBe 2
        pendingIds() shouldHaveSize 0
    }

    test("릴레이는 payload 만 보낸다 — 발행 시점에 재고를 다시 조회하지 않는다") {
        // Given: 예약을 하나 넣고 **먼저 발행**한다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        val reservationId = reserve(roomTypeId)
        val firstEventId = pendingIds().first()
        relay.drain()

        // When: 그 뒤에 취소가 일어나고 다시 발행한다
        inventoryService.cancel(reservationId)
        relay.drain()

        // Then: 첫 통보의 본문은 여전히 remaining 9 다.
        //
        // 발행 시점에 재고를 다시 읽는 구현이면 두 통보가 모두 10 으로 나가고,
        // 채널은 "9였던 적이 없다" 고 보게 된다. 그것은 at-least-once 가 아니라
        // 순서 없는 최신값 전송이고, 수신 측 멱등으로 흡수되지 않는다
        adapter.payloadOf(firstEventId)!! shouldContain "\"remaining\": 9"
        adapter.applied shouldBe 2
    }

    // ── T4 ────────────────────────────────────────────────────────────────
    test("T4 — 마킹 직전에 죽어도 재발행의 실질 부작용은 1회다") {
        // Given: 통보 1건
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        val eventId = pendingIds().single()

        // When ①: 이벤트를 집어 채널 호출까지 성공했는데 마킹 직전에 죽는다
        val now = Instant.now()
        val claimed = relay.claimPending(10, now).single()
        val first = adapter.push(claimed.id, claimed.payload)
        first.shouldBeInstanceOf<ChannelSyncResult.Success>().deduplicated shouldBe false
        // markPublished 를 부르지 않는다. 이것이 그 창이다.
        statusOf(eventId) shouldBe "PENDING"

        // 죽은 인스턴스가 쥔 임대가 살아 있는 동안에는 아무도 집지 않는다.
        // 이것이 없으면 발행이 느린 채널에서 중복 호출이 계속 늘어난다
        relay.drain(now = now).handled shouldBe 0

        // When ②: 임대가 만료된 뒤 다른 인스턴스(또는 재기동한 자신)가 집는다
        val report = relay.drain(now = now.plus(Duration.ofMinutes(2)))

        // Then: 호출은 두 번 나갔지만 **부작용은 한 번**이다
        report.published shouldBe 1
        adapter.attempts shouldBe 2
        adapter.applied shouldBe 1
        statusOf(eventId) shouldBe "PUBLISHED"
    }

    test("멱등키가 outbox_event.id 다 — 다른 이벤트는 흡수되지 않는다") {
        // Given: 서로 다른 통보 2건 (2박)
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        reserve(roomTypeId, nights = 2)

        // When
        relay.drain()

        // Then: 키가 이벤트별로 다르므로 둘 다 반영된다. 키를 예약 id 로 잡으면
        // 같은 예약의 다른 날짜 통보가 서로를 흡수해 하나만 나간다
        adapter.applied shouldBe 2
    }

    // ── 결과 4종의 분기 ───────────────────────────────────────────────────
    test("레이트 리밋은 실패가 아니다 — DEAD 로 보내지 않고 최소 1분 뒤로 미룬다") {
        // Given: 채널이 429 를 준다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        val eventId = pendingIds().single()
        adapter.forcedResult = ChannelSyncResult.RateLimited(retryAfterSeconds = null)

        // When
        val now = Instant.now()
        val report = relay.drain(now = now)

        // Then: 이것을 Permanent 로 오분류하면 정상 채널의 이벤트가 통째로
        // DLQ 로 떨어진다. 그리고 조용하다 -- 채널은 아무 문제도 보고하지 않았다
        report.rateLimited shouldBe 1
        report.dead shouldBe 0
        statusOf(eventId) shouldBe "PENDING"

        val nextAttempt = jdbc.queryForObject(
            "SELECT EXTRACT(EPOCH FROM (next_attempt_at - ?)) FROM outbox_event WHERE id = ?",
            Double::class.java,
            java.sql.Timestamp.from(now),
            eventId,
        )!!
        // 429 는 최소 1분 중지를 요구한다 (Channex 공개 문서)
        nextAttempt.toLong() shouldBeGreaterThan 55L
    }

    test("채널이 알려 준 대기 시간이 1분보다 길면 그쪽을 따른다") {
        // Given: Retry-After 가 5분
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        val eventId = pendingIds().single()
        adapter.forcedResult = ChannelSyncResult.RateLimited(retryAfterSeconds = 300)

        // When
        val now = Instant.now()
        relay.drain(now = now)

        // Then: 하한만 지키고 채널 지시를 무시하면 리밋을 다시 때린다
        val nextAttempt = jdbc.queryForObject(
            "SELECT EXTRACT(EPOCH FROM (next_attempt_at - ?)) FROM outbox_event WHERE id = ?",
            Double::class.java,
            java.sql.Timestamp.from(now),
            eventId,
        )!!
        nextAttempt.toLong() shouldBeGreaterThan 290L
    }

    test("일시 실패는 재시도로 미뤄지고 retry_count 가 오른다") {
        // Given: 5xx
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        val eventId = pendingIds().single()
        adapter.forcedResult = ChannelSyncResult.Retryable("502")

        // When
        relay.drain()

        // Then
        statusOf(eventId) shouldBe "PENDING"
        jdbc.queryForObject(
            "SELECT retry_count FROM outbox_event WHERE id = ?", Int::class.java, eventId,
        ) shouldBe 1
    }

    test("영구 실패는 DEAD 로 간다 — 재시도해도 결과가 같다") {
        // Given: 4xx
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        val eventId = pendingIds().single()
        adapter.forcedResult = ChannelSyncResult.Permanent("400 잘못된 룸타입")

        // When
        val report = relay.drain()

        // Then: 사람이 봐야 하는 자리다. 계속 재시도하면 그 사실이 큐 길이에 묻힌다
        report.dead shouldBe 1
        statusOf(eventId) shouldBe "DEAD"
    }

    test("어댑터가 예외를 던져도 이벤트를 잃지 않는다 — 재시도로 본다") {
        // Given: 어댑터가 결과 대신 예외를 던진다 (네트워크 끊김 등)
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        val eventId = pendingIds().single()
        adapter.forcedResult = null
        val throwing = object : dev.preagile.stayinventory.channel.ChannelAdapter {
            override val channel = "THROWING"
            override fun push(idempotencyKey: Long, payload: String) = error("연결 끊김")
        }
        val relayWithThrowingAdapter = OutboxRelay(jdbc, listOf(throwing))

        // When
        val report = relayWithThrowingAdapter.drain()

        // Then: 예외를 Permanent 로 읽으면 일시 장애에 DLQ 가 가득 찬다
        report.retried shouldBe 1
        statusOf(eventId) shouldBe "PENDING"
    }

    // ── 백오프 ────────────────────────────────────────────────────────────
    test("백오프 간격은 1·2·4·8·15·30분이고 마지막에서 멈춘다") {
        // Given / When / Then: 임의 값이 아니라 Channex 공개 문서의 실제
        // 재시도 스케줄이다. 여기를 바꾸면 근거가 사라진다
        relay.backoffFor(0) shouldBe Duration.ofMinutes(1)
        relay.backoffFor(1) shouldBe Duration.ofMinutes(2)
        relay.backoffFor(2) shouldBe Duration.ofMinutes(4)
        relay.backoffFor(3) shouldBe Duration.ofMinutes(8)
        relay.backoffFor(4) shouldBe Duration.ofMinutes(15)
        relay.backoffFor(5) shouldBe Duration.ofMinutes(30)
        // 무한히 벌어지지 않는다. 30분마다 다시 시도한다
        relay.backoffFor(99) shouldBe Duration.ofMinutes(30)
    }

    test("아직 시간이 안 된 이벤트는 집지 않는다") {
        // Given: 1분 뒤로 미뤄진 이벤트
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        adapter.forcedResult = ChannelSyncResult.Retryable("502")
        relay.drain()
        adapter.reset()

        // When: 지금 다시 돈다
        val report = relay.drain()

        // Then: next_attempt_at 을 무시하면 백오프가 있으나 마나다
        report.handled shouldBe 0
        adapter.attempts shouldBe 0
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
