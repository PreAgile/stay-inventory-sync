package dev.preagile.stayinventory.outbox

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.channel.ChannelAdapter
import dev.preagile.stayinventory.channel.ChannelSyncResult
import dev.preagile.stayinventory.inventory.InventoryFixture
import dev.preagile.stayinventory.inventory.InventoryService
import dev.preagile.stayinventory.inventory.ReserveCommand
import dev.preagile.stayinventory.inventory.runConcurrentlyOrFail
import dev.preagile.stayinventory.outbox.relay.OutboxRelay
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * **B3 — 다중 인스턴스에서 같은 이벤트를 두 번 처리하지 않는다.**
 *
 * 구현은 한 문장이지만 다중 인스턴스 안전성을 확보한다. 백로그에서 **비용 대비
 * 효과가 가장 높은 항목**으로 꼽힌 이유가 그것이다.
 *
 * ## 이 스펙이 세는 것
 *
 * `applied`(부작용)가 아니라 **`attempts`(호출 횟수)** 를 본다. 멱등키가 있으므로
 * 두 인스턴스가 같은 이벤트를 집어도 부작용은 한 번이다 -- 즉 `applied` 만
 * 보면 `SKIP LOCKED` 가 없어도 통과한다.
 *
 * 중복 집기가 실제로 태우는 것은 **레이트 리밋**이다. 분당 20건 상한에서
 * 인스턴스를 둘로 늘렸더니 처리량이 그대로이고 호출만 두 배가 되는 것이
 * 이 항목이 막으려는 상황이다.
 */
@SpringBootTest
@Import(PostgresTestContainer::class)
class OutboxRelayMultiInstanceTest(
    private val dataSource: DataSource,
    private val jdbc: JdbcTemplate,
    private val inventoryService: InventoryService,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)
    val fixture = InventoryFixture(dataSource)

    beforeTest { fixture.wipe() }

    /** 호출 횟수만 세는 어댑터. 인스턴스마다 따로 둔다. */
    class CountingAdapter(private val shared: SharedCounters) : ChannelAdapter {
        override val channel = "COUNTING"

        // 이 스펙은 호출 횟수만 본다. 대사는 InventoryDiffTest 의,
        // 규칙 발행은 ChannelCapTest 의 관심사다.
        override fun pushPolicy(idempotencyKey: Long, payload: String): ChannelSyncResult =
            push(idempotencyKey, payload)

        override fun currentInventory(
            roomTypeId: Long,
            from: java.time.LocalDate,
            to: java.time.LocalDate,
        ): Map<java.time.LocalDate, Int> = emptyMap()

        override fun push(idempotencyKey: Long, payload: String): ChannelSyncResult {
            shared.attempts.incrementAndGet()
            val first = shared.seen.putIfAbsent(idempotencyKey, true) == null
            if (first) shared.applied.incrementAndGet()
            return ChannelSyncResult.Success(deduplicated = !first)
        }
    }

    fun seedEvents(count: Int): Long {
        val roomTypeId = fixture.seedGrid(march1, days = count + 1, physicalTotal = 100)
        // 하루짜리 예약 count 건 -> 통보 count 건
        repeat(count) { i ->
            inventoryService.reserve(
                ReserveCommand(
                    roomTypeId = roomTypeId,
                    checkIn = march1.plusDays(i.toLong()),
                    checkOut = march1.plusDays(i + 1L),
                    roomCount = 1,
                    channel = "CHANNEL_A",
                    channelReservationId = UUID.randomUUID().toString(),
                    guestName = "손님$i",
                ),
            )
        }
        return roomTypeId
    }

    test("B3 — 인스턴스 2대가 동시에 돌아도 각 이벤트는 정확히 한 번만 호출된다") {
        // Given: 통보 60건과, 서로 다른 릴레이 인스턴스 2대
        seedEvents(60)
        val shared = SharedCounters()
        val instances = listOf(
            OutboxRelay(jdbc, listOf(CountingAdapter(shared))),
            OutboxRelay(jdbc, listOf(CountingAdapter(shared))),
        )
        val perInstancePublished = listOf(AtomicInteger(), AtomicInteger())

        // When: 둘이 동시에 폴링한다
        runConcurrentlyOrFail(2) { index ->
            repeat(10) {
                val report = instances[index].drain(limit = 10)
                perInstancePublished[index].addAndGet(report.published)
            }
        }

        // Then: 호출이 정확히 60이다.
        //
        // 임대가 없으면 두 인스턴스가 같은 배치를 집어 호출이 최대 120 이 된다.
        // 부작용은 멱등키가 막아 주므로 60 그대로이고, 그래서 applied 만 보면
        // 이 결함이 보이지 않는다 -- 레이트 리밋만 두 배로 태운다
        shared.attempts.get() shouldBe 60
        shared.applied.get() shouldBe 60

        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE status = 'PENDING'", Int::class.java,
        ) shouldBe 0

        // 두 인스턴스가 모두 일했다. 한쪽이 전부 가져가면 SKIP LOCKED 가
        // 처리량에 기여한다는 주장이 성립하지 않는다
        perInstancePublished.forEach { it.get() shouldBeGreaterThan 0 }
        perInstancePublished.sumOf { it.get() } shouldBe 60
    }

    test("임대 중인 이벤트는 다른 인스턴스에 보이지 않는다") {
        // Given: 통보 5건
        seedEvents(5)
        val shared = SharedCounters()
        val a = OutboxRelay(jdbc, listOf(CountingAdapter(shared)))
        val b = OutboxRelay(jdbc, listOf(CountingAdapter(shared)))

        // When: A 가 전부 집어 임대만 걸어 두고 아직 발행하지 않았다
        val claimed = a.claimPending(limit = 5)
        claimed.size shouldBe 5

        // Then: B 는 아무것도 못 집는다
        b.claimPending(limit = 5).size shouldBe 0

        // 임대가 만료되면 다시 보인다 -- 죽은 인스턴스가 이벤트를 영구히
        // 붙잡지 못한다는 뜻이다
        b.claimPending(limit = 5, now = java.time.Instant.now().plusSeconds(120)).size shouldBe 5
    }

    test("집은 이벤트는 아직 PENDING 이다 — 임대는 상태가 아니라 시각이다") {
        // Given / When
        seedEvents(3)
        val shared = SharedCounters()
        OutboxRelay(jdbc, listOf(CountingAdapter(shared))).claimPending(limit = 3)

        // Then: 별도의 PROCESSING 상태를 두지 않았다. 두면 그 상태에서 죽은
        // 이벤트를 되살릴 청소 작업이 또 필요해지고, 그 작업이 언제 도는지가
        // 새로운 정합성 질문이 된다. 시각 하나로 임대와 백오프를 함께 표현한다
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE status = 'PENDING'", Int::class.java,
        ) shouldBe 3
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}

/** 인스턴스가 달라도 채널은 하나다. 호출 횟수를 채널 쪽에서 센다. */
class SharedCounters {
    val attempts = AtomicInteger()
    val applied = AtomicInteger()
    val seen = ConcurrentHashMap<Long, Boolean>()
}
