package dev.preagile.stayinventory.inventory

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.domain.ReservationStatus
import dev.preagile.stayinventory.persistence.ReservationRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.sql.SQLException
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * **T5 · T6 — 확정 경로의 거울상.**
 *
 * ```
 * T5  CONFIRMED 1건에 동시 취소 50건  →  복원 정확히 1회    지우면 실패해야: AND status = 'CONFIRMED'
 * T6  잔여 0 + 취소 1 + 신규 예약 100  →  데드락 0, 성공 1   지우면 실패해야: reservation -> daily_inventory 순서
 * ```
 *
 * `T1` · `T2` 가 차감 경합을 증명하는데 복원 쪽에는 대응물이 없었다. 그 대칭을 채운다.
 *
 * `T2` 와 `T6` 의 역할이 다르다 -- `T2` 는 **한 테이블 안**의 순서를,
 * `T6` 은 **테이블 사이**의 순서를 증명한다 (ADR-0011).
 *
 * 여기서도 `#48` 과 같은 사정이 있다. 코드 경로가 하나면 순서는 언제나 일치하므로
 * "순서를 뒤집으면 T6 이 실패한다" 를 이 코드에 적용할 수 없다. 대신 뒤집힌
 * 순서를 **테스트 안에서 직접 만들어** 순환이 실제로 성립하는 것을 보인다.
 */
@SpringBootTest
@Import(PostgresTestContainer::class)
class InventoryCancelConcurrencyTest(
    private val dataSource: DataSource,
    private val inventoryService: InventoryService,
    private val reservations: ReservationRepository,
    private val transactionTemplate: org.springframework.transaction.support.TransactionTemplate,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)
    val fixture = InventoryFixture(dataSource)

    beforeTest { fixture.wipe() }

    fun runConcurrently(count: Int, task: (Int) -> Unit) =
        runConcurrentlyOrFail(count, task)

    fun reserve(roomTypeId: Long, nights: Long = 2, roomCount: Int = 1): Long =
        inventoryService.reserve(
            ReserveCommand(
                roomTypeId = roomTypeId,
                checkIn = march1,
                checkOut = march1.plusDays(nights),
                roomCount = roomCount,
                channel = "CHANNEL_A",
                channelReservationId = UUID.randomUUID().toString(),
                guestName = "김손님",
            ),
        ).shouldBeInstanceOf<ReserveResult.Reserved>().reservationId

    // ── T5 ────────────────────────────────────────────────────────────────
    test("T5 — 같은 예약에 동시 취소 50건이 와도 복원은 정확히 1회다") {
        // Given: 3객실 확정 예약
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        val reservationId = reserve(roomTypeId, roomCount = 3)
        fixture.sold(roomTypeId, march1) shouldBe 3

        val results = ConcurrentLinkedQueue<CancelResult>()
        val errors = ConcurrentLinkedQueue<Throwable>()

        // When: 취소 웹훅도 at-least-once 다. 같은 취소가 여러 번 오는 것은
        // 예외가 아니라 계약의 일부다
        runConcurrently(50) {
            runCatching { inventoryService.cancel(reservationId) }
                .onSuccess { results += it }
                .onFailure { errors += it }
        }

        // Then
        errors.map { it.toString() } shouldHaveSize 0
        results.filterIsInstance<CancelResult.Restored>() shouldHaveSize 1
        results.count { it == CancelResult.AlreadyCanceled } shouldBe 49

        // 3 이 한 번만 돌아왔다. 두 번 돌아오면 sold 가 -3 이 되고,
        // 그 자리에 새 예약이 들어오면 그것이 오버부킹이다
        fixture.sold(roomTypeId, march1) shouldBe 0
        fixture.sold(roomTypeId, march1.plusDays(1)) shouldBe 0
    }

    test("T5 확장 — 예약 5건에 취소 250건이 섞여 들어와도 각각 한 번씩만 복원된다") {
        // Given: 서로 다른 예약 5건 (각 2객실)
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 20)
        val ids = (1..5).map { reserve(roomTypeId, roomCount = 2) }
        fixture.sold(roomTypeId, march1) shouldBe 10

        val restored = AtomicInteger()

        // When: 각 예약에 50번씩, 전부 섞어서
        runConcurrently(50) {
            ids.shuffled().forEach { id ->
                if (inventoryService.cancel(id) is CancelResult.Restored) restored.incrementAndGet()
            }
        }

        // Then: 예약당 정확히 한 번. 한 예약의 복원이 다른 예약의 판정에
        // 끼어들면 여기서 5 가 아닌 수가 나온다
        restored.get() shouldBe 5
        // 둘째 숙박일까지 본다. 첫 날짜만 보면 복원이 한 날짜만 처리하는
        // 회귀가 생겨도 통과한다 (reserve 기본값이 2박이다)
        fixture.sold(roomTypeId, march1) shouldBe 0
        fixture.sold(roomTypeId, march1.plusDays(1)) shouldBe 0
    }

    // ── T6 ────────────────────────────────────────────────────────────────
    test("T6 — 취소와 신규 예약이 같은 날짜에서 교차해도 데드락이 없고 자리는 한 번만 팔린다") {
        // Given: 물리 1 인 날짜가 확정 예약 1건으로 막혀 있다
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 1)
        val existing = reserve(roomTypeId)
        fixture.sold(roomTypeId, march1) shouldBe 1

        val deadlocks = ConcurrentLinkedQueue<String>()
        val succeeded = AtomicInteger()

        // When: 취소 1건과 신규 예약 100건이 동시에 출발한다.
        //
        // 신규 예약은 재시도한다. 취소가 언제 커밋되는지는 스케줄러가 정하므로,
        // 한 번만 쏘면 "취소보다 먼저 도착해서 전부 실패" 와 "자리가 났는데
        // 아무도 없었다" 를 구분할 수 없다. 재시도가 그 타이밍 의존을 없앤다
        runConcurrently(101) { index ->
            if (index == 0) {
                runCatching { inventoryService.cancel(existing) }
                    .onFailure { deadlocks += it.toString() }
                return@runConcurrently
            }

            // 전역 성공이 관측되면 멈춘다. 자기 성공만 보고 끝내면 승자 1명을
            // 뺀 99개가 데드라인까지 같은 두 행에 FOR UPDATE 를 계속 던진다.
            // 커넥션 풀이 20 이므로 대기가 중첩되고, lock_timeout(5s)에 걸린
            // 55P03 이 데드락 카운트에 섞여 **정확성 위반이 없는데도 실패**한다.
            //
            // 두 번째 성공이 생기면 succeeded 가 2 가 되므로 "자리는 한 번만
            // 팔린다" 는 증명력은 그대로다.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (System.nanoTime() < deadline && succeeded.get() == 0) {
                val outcome = runCatching {
                    inventoryService.reserve(
                        ReserveCommand(
                            roomTypeId, march1, march1.plusDays(2), 1,
                            "CHANNEL_A", UUID.randomUUID().toString(), "손님$index",
                        ),
                    )
                }
                outcome.onFailure { deadlocks += it.toString() }
                if (outcome.getOrNull() is ReserveResult.Reserved) {
                    succeeded.incrementAndGet()
                    return@runConcurrently
                }
                Thread.sleep(5)
            }
        }

        // Then: 취소가 만든 자리 하나가 정확히 한 건에게만 간다
        deadlocks shouldHaveSize 0
        succeeded.get() shouldBe 1
        fixture.sold(roomTypeId, march1) shouldBe 1

        reservations.findById(existing).orElseThrow().status shouldBe ReservationStatus.CANCELED
        reservations.findAll().count { it.status == ReservationStatus.CONFIRMED } shouldBe 1
    }

    test("테이블 간 순서가 어긋나면 실제로 데드락이 난다 — ADR-0011 이 막고 있던 것") {
        // Given: 확정 예약 하나와 그 날짜 재고 행 하나
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 5)
        val reservationId = reserve(roomTypeId, nights = 1)

        val bothHoldFirstLock = CountDownLatch(2)
        val failures = ConcurrentLinkedQueue<SQLException>()

        // When: 한쪽은 규칙대로 reservation -> daily_inventory,
        //       다른 쪽은 뒤집어서 daily_inventory -> reservation 으로 잠근다
        runConcurrently(2) { thread ->
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    if (thread == 0) {
                        conn.lockReservation(reservationId)
                        bothHoldFirstLock.countDown()
                        bothHoldFirstLock.await(10, TimeUnit.SECONDS)
                        conn.lockInventory(roomTypeId, march1)
                    } else {
                        conn.lockInventory(roomTypeId, march1)
                        bothHoldFirstLock.countDown()
                        bothHoldFirstLock.await(10, TimeUnit.SECONDS)
                        conn.lockReservation(reservationId)
                    }
                    conn.commit()
                } catch (e: SQLException) {
                    failures += e
                    conn.rollback()
                }
            }
        }

        // Then: 정확히 한쪽이 데드락 희생자로 죽는다.
        //
        // 40P01 이어야 한다. 55P03(lock_timeout)이면 "순환" 이 아니라 "느렸다" 는
        // 뜻이고 그건 이 테스트가 증명하려는 것이 아니다
        failures shouldHaveSize 1
        failures.first().sqlState shouldBe "40P01"
    }

    test("규칙대로 잠그면 같은 두 행이어도 데드락이 없다 — 대조군") {
        // Given: 위와 같은 상황. 순서만 양쪽 다 reservation -> daily_inventory
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 5)
        val reservationId = reserve(roomTypeId, nights = 1)

        val bothHoldFirstLock = CountDownLatch(2)
        val failures = ConcurrentLinkedQueue<SQLException>()
        val committed = AtomicInteger()

        // When
        runConcurrently(2) {
            dataSource.connection.use { conn ->
                conn.autoCommit = false
                try {
                    conn.lockReservation(reservationId)
                    bothHoldFirstLock.countDown()
                    bothHoldFirstLock.await(2, TimeUnit.SECONDS)
                    conn.lockInventory(roomTypeId, march1)
                    conn.commit()
                    committed.incrementAndGet()
                } catch (e: SQLException) {
                    failures += e
                    conn.rollback()
                }
            }
        }

        // Then
        failures shouldHaveSize 0
        committed.get() shouldBe 2
    }

    test("조건부 UPDATE 는 트랜잭션 밖에서 실행되지 않는다") {
        // Given: 확정 예약
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 5)
        val reservationId = reserve(roomTypeId, nights = 1)

        // When: 트랜잭션 없이 부른다
        val thrown = runCatching {
            reservations.transition(
                reservationId, ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELED, Instant.now(),
            )
        }.exceptionOrNull()

        // Then: 막히는 것이 맞다. 이 문장이 잡는 행 락은 **커밋까지** 유지돼야
        // 뒤이은 재고 락과 한 트랜잭션을 이루는데, 트랜잭션 밖이면 문장이 끝나는
        // 순간 락도 풀린다. 그러면 락 순서를 지켜도 지킨 것이 아니다
        (thrown != null) shouldBe true
    }

    test("한 트랜잭션 안에서 조건부 UPDATE 는 한 번만 1 을 돌려준다") {
        // Given: 확정 예약
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 5)
        val reservationId = reserve(roomTypeId, nights = 1)

        // When / Then: 이 숫자가 곧 "내가 옮겼다" 의 증거이고, 복원을 실행할지
        // 말지를 이것만으로 판정한다. 상태를 읽어서 비교하는 방식이면 읽기와
        // 쓰기 사이가 열리고, T5 가 거기서 깨진다
        transactionTemplate.executeWithoutResult {
            reservations.transition(
                reservationId, ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELED, Instant.now(),
            ) shouldBe 1
            reservations.transition(
                reservationId, ReservationStatus.CONFIRMED,
                ReservationStatus.CANCELED, Instant.now(),
            ) shouldBe 0
        }

        // 재고는 이 경로로 건드리지 않았으므로 사실과 맞춰 둔다 (INV-2)
        fixture.forceSold(roomTypeId, march1, 0)
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}

private fun java.sql.Connection.lockReservation(id: Long) {
    prepareStatement("SELECT status FROM reservation WHERE id = ? FOR UPDATE").use { ps ->
        ps.setLong(1, id)
        ps.executeQuery().use { it.next() }
    }
}

private fun java.sql.Connection.lockInventory(roomTypeId: Long, stayDate: LocalDate) {
    prepareStatement(
        "SELECT sold FROM daily_inventory WHERE room_type_id = ? AND stay_date = ? FOR UPDATE",
    ).use { ps ->
        ps.setLong(1, roomTypeId)
        ps.setObject(2, stayDate)
        ps.executeQuery().use { it.next() }
    }
}
