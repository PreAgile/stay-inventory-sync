package dev.preagile.stayinventory.inventory

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.domain.ReservationStatus
import dev.preagile.stayinventory.persistence.OutboxEventRepository
import dev.preagile.stayinventory.persistence.ReservationRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * 복원의 **단일 경로 동작**. 경합은 [InventoryCancelConcurrencyTest] 가 본다.
 *
 * 차감의 거울상인데 **대칭이 아닌 지점이 하나** 있고, 이 스펙의 절반이 그것이다 --
 * 복원은 `CONFIRMED -> CANCELED` 에서만 일어난다 (절대 규칙 7). 선점에서 오는
 * 종료는 차감된 적이 없으므로 되돌릴 것이 없다. 이 비대칭을 놓치면 재고가
 * 부풀어 오르고, 부푼 자리에 새 예약이 들어오면 그것이 오버부킹이다.
 */
@SpringBootTest
@Import(PostgresTestContainer::class)
class InventoryRestoreTest(
    private val dataSource: DataSource,
    private val inventoryService: InventoryService,
    private val reservations: ReservationRepository,
    private val outbox: OutboxEventRepository,
    private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)
    val fixture = InventoryFixture(dataSource)

    beforeTest { fixture.wipe() }

    fun reserve(roomTypeId: Long, nights: Long = 3, roomCount: Int = 1): Long =
        inventoryService.reserve(
            ReserveCommand(
                roomTypeId = roomTypeId,
                checkIn = march1,
                checkOut = march1.plusDays(nights),
                roomCount = roomCount,
                channel = "AIRBNB",
                channelReservationId = UUID.randomUUID().toString(),
                guestName = "김손님",
            ),
        ).shouldBeInstanceOf<ReserveResult.Reserved>().reservationId

    // ── 정상 복원 ─────────────────────────────────────────────────────────
    test("취소하면 예약이 차지했던 날짜만큼 재고가 돌아온다") {
        // Given: 3박 2객실 예약
        val roomTypeId = fixture.seedGrid(march1, days = 4, physicalTotal = 10)
        val reservationId = reserve(roomTypeId, roomCount = 2)

        // When
        val result = inventoryService.cancel(reservationId)

        // Then: 1 이 아니라 room_count 만큼 돌아온다 (A7)
        result.shouldBeInstanceOf<CancelResult.Restored>().roomCount shouldBe 2
        fixture.sold(roomTypeId, march1) shouldBe 0
        fixture.sold(roomTypeId, march1.plusDays(1)) shouldBe 0
        fixture.sold(roomTypeId, march1.plusDays(2)) shouldBe 0

        reservations.findById(reservationId).orElseThrow()
            .status shouldBe ReservationStatus.CANCELED
    }

    test("복원한 자리는 다시 팔린다") {
        // Given: 물리 1 인 날짜가 예약으로 막혔다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 1)
        val first = reserve(roomTypeId, nights = 1)
        inventoryService.reserve(
            ReserveCommand(
                roomTypeId, march1, march1.plusDays(1), 1,
                "AIRBNB", UUID.randomUUID().toString(), "이손님",
            ),
        ).shouldBeInstanceOf<ReserveResult.Rejected>()

        // When: 첫 예약을 취소한다
        inventoryService.cancel(first)

        // Then: 복원이 카운터만 내리고 실제로 팔 수 있게 만들지 않으면
        // 그것은 복원이 아니라 숫자 놀음이다
        inventoryService.reserve(
            ReserveCommand(
                roomTypeId, march1, march1.plusDays(1), 1,
                "AIRBNB", UUID.randomUUID().toString(), "박손님",
            ),
        ).shouldBeInstanceOf<ReserveResult.Reserved>()
    }

    test("취소 통보가 날짜마다, 같은 트랜잭션에 적힌다") {
        // Given: 3박 예약 (통보 3건이 이미 있다)
        val roomTypeId = fixture.seedGrid(march1, days = 4, physicalTotal = 10)
        val reservationId = reserve(roomTypeId)
        outbox.count() shouldBe 3

        // When
        inventoryService.cancel(reservationId)

        // Then: 취소분 3건이 더 붙는다. cause 로 차감분과 구분된다
        val cancelEvents = outbox.findAll()
            .filter { objectMapper.readTree(it.payload)["cause"].asText() == "CANCELED" }
        cancelEvents shouldHaveSize 3
        cancelEvents.map { objectMapper.readTree(it.payload)["remaining"].asInt() }
            .toSet() shouldBe setOf(10)
    }

    // ── 비대칭 ────────────────────────────────────────────────────────────
    test("이미 취소된 예약을 다시 취소해도 재고가 두 번 돌아오지 않는다") {
        // Given: 취소된 예약
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 5)
        val reservationId = reserve(roomTypeId, nights = 1)
        inventoryService.cancel(reservationId)

        // When: 같은 취소가 또 온다. at-least-once 웹훅에서 일상적인 입력이다
        val second = inventoryService.cancel(reservationId)

        // Then: 실패가 아니라 멱등한 성공이다. sold 가 음수로 가면
        // DB CHECK 가 막지만, 막히기 전에 이미 계산이 틀린 것이다
        second shouldBe CancelResult.AlreadyCanceled
        fixture.sold(roomTypeId, march1) shouldBe 0
    }

    test("선점 상태 예약을 취소해도 재고는 건드리지 않는다 — 차감된 적이 없다") {
        // Given: HELD 상태 예약. 선점은 sold 에 들어간 적이 없다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 5)
        val heldId = fixture.insertReservation(
            roomTypeId, march1, march1.plusDays(1), ReservationStatus.HELD, roomCount = 2,
        )

        // When
        val result = inventoryService.cancel(heldId)

        // Then: WHERE status = 'CONFIRMED' 가 걸러낸다. 여기서 복원하면
        // 차감한 적 없는 재고가 늘어나 sold 가 음수로 간다
        result shouldBe CancelResult.AlreadyCanceled
        fixture.sold(roomTypeId, march1) shouldBe 0
        reservations.findById(heldId).orElseThrow().status shouldBe ReservationStatus.HELD
    }

    test("노쇼로 끝난 예약을 취소해도 복원하지 않는다 — 그 날짜는 이미 소비됐다") {
        // Given: TERMINATED 예약과 그만큼 차감된 재고
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 5)
        val terminatedId = fixture.insertReservation(
            roomTypeId, march1, march1.plusDays(1), ReservationStatus.TERMINATED, roomCount = 1,
        )
        fixture.forceSold(roomTypeId, march1, 1)

        // When
        val result = inventoryService.cancel(terminatedId)

        // Then: TERMINATED 는 점유 집합에 있다. 복원하면 INV-2 가 깨진다 --
        // sold 는 0 인데 점유 예약 합은 1 이 된다
        result shouldBe CancelResult.AlreadyCanceled
        fixture.sold(roomTypeId, march1) shouldBe 1
    }

    test("없는 예약은 이미 취소된 것과 구분된다") {
        // Given: 아무것도 없다
        // When
        val result = inventoryService.cancel(999_999L)

        // Then: 없는 예약 취소는 데이터가 어긋났다는 신호다. 정상 재시도와
        // 뭉치면 진짜 이상이 재시도 노이즈에 묻힌다
        result shouldBe CancelResult.NotFound
    }

    test("취소 후에도 통보가 나가지 않은 상태로 남는다 — 릴레이가 가져간다") {
        // Given / When
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 5)
        val reservationId = reserve(roomTypeId, nights = 1)
        inventoryService.cancel(reservationId)

        // Then: 취소 트랜잭션 안에서 채널을 부르지 않는다. 부르면 락을 쥔 채
        // 네트워크를 기다리게 되고, 그 시간만큼 그 날짜가 멈춘다 (절대 규칙 4)
        outbox.findAll().all { it.publishedAt == null } shouldBe true
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
