package dev.preagile.stayinventory.inventory

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.persistence.DailyInventoryId
import dev.preagile.stayinventory.persistence.DailyInventoryRepository
import dev.preagile.stayinventory.persistence.ReservationRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.sql.SQLException
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

/**
 * **T1 · T2 — 이 저장소가 하는 주장의 본체.**
 *
 * ```
 * T1  잔여 1인 날짜에 동시 100건  →  성공 정확히 1건        지우면 실패해야: 재고 행 락
 * T2  겹치는 범위를 교차로 200회   →  데드락 0건            지우면 실패해야: stay_date 정렬
 * ```
 *
 * T1 의 100 은 임의의 수가 아니다. 커넥션 풀(20)을 넘기도록 잡아 **풀이 고갈된
 * 상태에서도 정확성이 유지되는지**를 함께 본다.
 *
 * ## T2 의 증명 방식에 대하여
 *
 * `03-testing-strategy.md` 는 *"정렬 로직을 제거하면 T2 가 실패해야 한다"* 고
 * 적었는데, **이 구현에서는 그 제거가 표현되지 않는다.** 날짜 목록이
 * `generateSequence(checkIn) { it.plusDays(1) }` 로 만들어져 **생성 자체가
 * 오름차순**이기 때문이다. `sorted()` 한 줄을 지우는 식으로 뒤집을 대상이 없고,
 * 두 스레드 모두 내림차순으로 바꿔도 **여전히 순서가 일치**하므로 데드락은 나지 않는다.
 *
 * 데드락에 필요한 것은 오름차순이 아니라 **순서의 일치**다. 그래서 증명을 둘로 나눈다.
 *
 * | 무엇 | 어떻게 |
 * |---|---|
 * | 순서가 어긋나면 실제로 데드락이 난다 | 반대 순서로 잠그는 두 트랜잭션을 래치로 교차시킨다 |
 * | 이 구현은 순서가 어긋날 수 없다 | 실제 서비스로 200회 교차. 그리고 `stayDates()` 단위 테스트 |
 *
 * 첫째가 없으면 둘째는 "데드락이 원래 안 나는 상황이었다" 와 구분되지 않는다.
 */
@SpringBootTest
@Import(PostgresTestContainer::class)
class InventoryConcurrencyTest(
    private val dataSource: DataSource,
    private val inventoryService: InventoryService,
    private val inventories: DailyInventoryRepository,
    private val reservations: ReservationRepository,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)
    val fixture = InventoryFixture(dataSource)

    beforeTest { fixture.wipe() }

    fun command(roomTypeId: Long, checkIn: LocalDate, checkOut: LocalDate, roomCount: Int = 1) =
        ReserveCommand(
            roomTypeId = roomTypeId,
            checkIn = checkIn,
            checkOut = checkOut,
            roomCount = roomCount,
            channel = "AIRBNB",
            channelReservationId = UUID.randomUUID().toString(),
            guestName = "김손님",
        )

    fun runConcurrently(count: Int, task: (Int) -> Unit) = runConcurrentlyOrFail(count, task)

    // ── T1 ────────────────────────────────────────────────────────────────
    test("T1 — 잔여 1인 날짜에 동시 100건이 들어와도 성공은 정확히 1건이다") {
        // Given: 물리 1, 하루짜리 격자
        val roomTypeId = fixture.seedGrid(march1, days = 1, physicalTotal = 1)
        val results = ConcurrentLinkedQueue<ReserveResult>()
        val errors = ConcurrentLinkedQueue<Throwable>()

        // When: 100개 스레드가 같은 날짜를 동시에 요청한다.
        // 커넥션 풀은 20 이므로 80 개는 커넥션조차 못 잡고 기다린다
        runConcurrently(100) {
            runCatching {
                inventoryService.reserve(command(roomTypeId, march1, march1.plusDays(1)))
            }.onSuccess { results += it }.onFailure { errors += it }
        }

        // Then: 예외로 끝난 요청이 있으면 그것은 "실패" 가 아니라 **판정 불가**다.
        // 재고 부족은 Rejected 로 와야 하고, 예외는 락이나 풀이 깨졌다는 뜻이다
        errors.map { it.toString() } shouldHaveSize 0

        results.filterIsInstance<ReserveResult.Reserved>() shouldHaveSize 1
        results.filterIsInstance<ReserveResult.Rejected>() shouldHaveSize 99

        // 카운터와 사실이 모두 1 이다
        inventories.findById(DailyInventoryId(roomTypeId, march1)).orElseThrow().sold shouldBe 1
        reservations.count() shouldBe 1
    }

    test("T1 확장 — 잔여 3에 동시 100건이면 정확히 3건이 성공한다") {
        // Given: 잔여가 1 일 때만 통과하는 방어(예: 조건을 == 0 으로 잘못 쓴 코드)가
        // 있을 수 있다. 상한이 1 이 아닌 경우도 본다
        val roomTypeId = fixture.seedGrid(march1, days = 1, physicalTotal = 3)
        val results = ConcurrentLinkedQueue<ReserveResult>()

        // When
        runConcurrently(100) {
            results += inventoryService.reserve(command(roomTypeId, march1, march1.plusDays(1)))
        }

        // Then
        results.filterIsInstance<ReserveResult.Reserved>() shouldHaveSize 3
        inventories.findById(DailyInventoryId(roomTypeId, march1)).orElseThrow().sold shouldBe 3
    }

    test("T1 다객실 — 잔여 5에 2객실씩 동시 요청이면 2건만 성공한다") {
        // Given: 5 // 2 = 2. 마지막 1 개는 2객실 요청을 받을 수 없다
        val roomTypeId = fixture.seedGrid(march1, days = 1, physicalTotal = 5)
        val results = ConcurrentLinkedQueue<ReserveResult>()

        // When
        runConcurrently(50) {
            results += inventoryService.reserve(
                command(roomTypeId, march1, march1.plusDays(1), roomCount = 2),
            )
        }

        // Then: room_count 를 1 로 세는 구현이면 여기서 5건이 성공하고 sold 가 10 이 된다
        results.filterIsInstance<ReserveResult.Reserved>() shouldHaveSize 2
        inventories.findById(DailyInventoryId(roomTypeId, march1)).orElseThrow().sold shouldBe 4
    }

    // ── T2 ────────────────────────────────────────────────────────────────
    test("T2 — 겹치는 다일 예약을 교차로 200회 보내도 데드락이 없다") {
        // Given: 3/1 ~ 3/8 격자. 두 요청 범위가 3/3 ~ 3/5 에서 겹친다
        val roomTypeId = fixture.seedGrid(march1, days = 8, physicalTotal = 1000)
        val rangeA = march1 to march1.plusDays(5)          // 3/1 ~ 3/5
        val rangeB = march1.plusDays(2) to march1.plusDays(7) // 3/3 ~ 3/7

        val deadlocks = ConcurrentLinkedQueue<String>()
        val outcomes = ConcurrentLinkedQueue<ReserveResult>()

        // When: 두 스레드가 서로 반대 순서로 200회씩 요청한다
        runConcurrently(2) { thread ->
            val order = if (thread == 0) listOf(rangeA, rangeB) else listOf(rangeB, rangeA)
            repeat(200) {
                order.forEach { (from, to) ->
                    runCatching { inventoryService.reserve(command(roomTypeId, from, to)) }
                        .onSuccess { outcomes += it }
                        .onFailure { e -> deadlocks += e.toString() }
                }
            }
        }

        // Then: 데드락 0. 그리고 모든 요청이 성공 또는 재고부족으로 **명확히** 끝났다
        deadlocks shouldHaveSize 0
        outcomes shouldHaveSize 800
    }

    test("순서가 어긋나면 실제로 데드락이 난다 — 정렬이 막고 있던 것") {
        // Given: 두 날짜와, 그 둘을 반대 순서로 잠그려는 두 트랜잭션
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        val day1 = march1
        val day2 = march1.plusDays(1)

        val bothHoldFirstLock = CountDownLatch(2)
        val failures = ConcurrentLinkedQueue<SQLException>()

        // When: A 는 3/1 -> 3/2, B 는 3/2 -> 3/1 순으로 잠근다.
        // 래치로 "둘 다 첫 락을 쥔 상태" 를 강제하므로 순환이 확정적으로 성립한다
        runConcurrently(2) { thread ->
            val order = if (thread == 0) listOf(day1, day2) else listOf(day2, day1)
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    conn.lockRow(roomTypeId, order[0])
                    bothHoldFirstLock.countDown()
                    bothHoldFirstLock.await(10, TimeUnit.SECONDS)
                    conn.lockRow(roomTypeId, order[1])
                    conn.commit()
                } catch (e: SQLException) {
                    failures += e
                    conn.rollback()
                }
            }
        }

        // Then: 정확히 한쪽이 데드락 희생자로 죽는다.
        //
        // SQLSTATE 를 확인하는 이유는 lock_timeout(5s, 55P03)과 구분하기 위해서다.
        // 55P03 이면 "순환" 이 아니라 "느렸다" 는 뜻이고, 그건 이 테스트가
        // 증명하려는 것이 아니다. deadlock_timeout(1s) 이 먼저 걸려야 한다
        failures shouldHaveSize 1
        failures.first().sqlState shouldBe "40P01"
    }

    test("같은 순서로 잠그면 같은 두 행이어도 데드락이 없다 — 대조군") {
        // Given: 위와 완전히 같은 상황인데 순서만 일치시킨다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        val ascending = listOf(march1, march1.plusDays(1))

        val bothHoldFirstLock = CountDownLatch(2)
        val failures = ConcurrentLinkedQueue<SQLException>()
        val committed = java.util.concurrent.atomic.AtomicInteger()

        // When
        runConcurrently(2) {
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    conn.lockRow(roomTypeId, ascending[0])
                    bothHoldFirstLock.countDown()
                    // 한쪽은 여기서 첫 락조차 못 잡고 기다린다. 그래서 await 는
                    // 타임아웃으로 풀려도 정상이다 -- 대기 자체가 순서 고정의 효과다
                    bothHoldFirstLock.await(2, TimeUnit.SECONDS)
                    conn.lockRow(roomTypeId, ascending[1])
                    conn.commit()
                    committed.incrementAndGet()
                } catch (e: SQLException) {
                    failures += e
                    conn.rollback()
                }
            }
        }

        // Then: 둘 다 살아서 커밋한다. 순서가 같으면 순환이 성립하지 않는다
        failures shouldHaveSize 0
        committed.get() shouldBe 2
    }

    test("다일 예약의 날짜 목록은 언제나 오름차순이다 — 뒤집을 자리가 없다") {
        // Given: 여러 길이의 요청
        listOf(1L, 2L, 5L, 30L).forEach { nights ->
            val dates = ReserveCommand(
                roomTypeId = 1,
                checkIn = march1,
                checkOut = march1.plusDays(nights),
                roomCount = 1,
                channel = "AIRBNB",
                channelReservationId = "x-$nights",
                guestName = "김손님",
            ).stayDates()

            // Then: 정렬을 "호출" 하는 것이 아니라 **생성이 오름차순**이다.
            // 그래서 지울 수 있는 한 줄이 없고, 순서가 어긋날 경로도 없다
            dates shouldBe dates.sorted()
            dates.size.toLong() shouldBe nights
        }
        1 shouldBeGreaterThan 0
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}

/** 그 날짜 재고 행을 `FOR UPDATE` 로 잠근다. 값은 바꾸지 않는다 -- 순수하게 뮤텍스다. */
private fun java.sql.Connection.lockRow(roomTypeId: Long, stayDate: LocalDate) {
    prepareStatement(
        "SELECT sold FROM daily_inventory " +
            "WHERE room_type_id = ? AND stay_date = ? FOR UPDATE",
    ).use { ps ->
        ps.setLong(1, roomTypeId)
        ps.setObject(2, stayDate)
        ps.executeQuery().use { it.next() }
    }
}

