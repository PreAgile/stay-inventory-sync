package dev.preagile.stayinventory.inventory

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.domain.ReservationStatus
import dev.preagile.stayinventory.persistence.DailyInventoryId
import dev.preagile.stayinventory.persistence.DailyInventoryRepository
import dev.preagile.stayinventory.persistence.OutboxEventRepository
import dev.preagile.stayinventory.persistence.ReservationRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * 재고 차감의 **단일 경로 동작**을 고정한다. 경합은 [InventoryConcurrencyTest] 가 본다.
 *
 * 여기서 지키는 것은 넷이다.
 *
 * - 반개구간 -- 체크아웃 당일을 차감하지 않는다 (절대 규칙 6)
 * - **부분 성공 없음** -- 하나라도 안 되면 아무것도 바뀌지 않는다
 * - 거절 사유를 뭉개지 않는다 -- 매진과 격자 없음은 다른 신호다
 * - Outbox 가 도메인 변경과 같은 트랜잭션에 적힌다 (절대 규칙 3)
 *
 * 불변식은 이 스펙이 검사하지 않는다. 공용 훅이 테스트마다 부른다.
 */
@SpringBootTest
@Import(PostgresTestContainer::class)
class InventoryDeductionTest(
    private val dataSource: DataSource,
    private val inventoryService: InventoryService,
    private val inventories: DailyInventoryRepository,
    private val reservations: ReservationRepository,
    private val outbox: OutboxEventRepository,
    private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)

    beforeTest { InventoryFixture(dataSource).wipe() }

    fun command(
        roomTypeId: Long,
        checkIn: LocalDate = march1,
        checkOut: LocalDate = march1.plusDays(3),
        roomCount: Int = 1,
    ) = ReserveCommand(
        roomTypeId = roomTypeId,
        checkIn = checkIn,
        checkOut = checkOut,
        roomCount = roomCount,
        channel = "CHANNEL_A",
        channelReservationId = UUID.randomUUID().toString(),
        guestName = "김손님",
    )

    fun soldOn(roomTypeId: Long, date: LocalDate): Int =
        inventories.findById(DailyInventoryId(roomTypeId, date)).orElseThrow().sold

    // ── 정상 경로 ─────────────────────────────────────────────────────────
    test("3박 예약은 세 날짜를 차감하고 체크아웃 당일은 건드리지 않는다") {
        // Given: 3/1 ~ 3/4 까지 격자가 있고 3박 예약이 들어온다
        val roomTypeId = InventoryFixture(dataSource).seedGrid(march1, days = 4, physicalTotal = 10)

        // When
        val result = inventoryService.reserve(command(roomTypeId, roomCount = 2))

        // Then
        result.shouldBeInstanceOf<ReserveResult.Reserved>()
        soldOn(roomTypeId, march1) shouldBe 2
        soldOn(roomTypeId, march1.plusDays(1)) shouldBe 2
        soldOn(roomTypeId, march1.plusDays(2)) shouldBe 2

        // 3/4 는 퇴실일이다. 여기를 차감하면 그날 방 하나가 영영 안 팔린다
        soldOn(roomTypeId, march1.plusDays(3)) shouldBe 0
    }

    test("차감 단위는 1 이 아니라 room_count 다") {
        // Given: A7. 한 예약이 여러 객실을 잡는다
        val roomTypeId = InventoryFixture(dataSource).seedGrid(march1, days = 2, physicalTotal = 10)

        // When
        inventoryService.reserve(command(roomTypeId, checkOut = march1.plusDays(1), roomCount = 3))

        // Then: 1 씩 세면 3객실 예약이 1객실만 차감되고, 그 차이가 곧 오버부킹이다
        soldOn(roomTypeId, march1) shouldBe 3
    }

    test("예약은 CONFIRMED 로 저장된다 — 이 경로에 선점 단계가 없다") {
        // Given: 즉시확정 경로. 선점(HELD)은 ADR-0010 의 별도 경로다
        val roomTypeId = InventoryFixture(dataSource).seedGrid(march1, days = 4, physicalTotal = 10)

        // When
        val result = inventoryService.reserve(command(roomTypeId))
            .shouldBeInstanceOf<ReserveResult.Reserved>()

        // Then
        reservations.findById(result.reservationId).orElseThrow()
            .status shouldBe ReservationStatus.CONFIRMED
    }

    // ── 거절 ──────────────────────────────────────────────────────────────
    test("한 날짜라도 매진이면 아무 날짜도 차감되지 않는다 — 부분 성공은 없다") {
        // Given: 3/1 과 3/3 은 여유가 있고 3/2 만 매진이다
        val fixture = InventoryFixture(dataSource)
        val roomTypeId = fixture.seedGrid(march1, days = 4, physicalTotal = 5)
        fixture.markSoldOut(roomTypeId, march1.plusDays(1))

        // When
        val result = inventoryService.reserve(command(roomTypeId))

        // Then: 3박 중 2박만 잡힌 예약은 손님에게도 숙소에게도 쓸모가 없다
        val rejected = result.shouldBeInstanceOf<ReserveResult.Rejected>()
        rejected.unavailable shouldHaveSize 1
        rejected.unavailable.first().stayDate shouldBe march1.plusDays(1)
        rejected.unavailable.first().reason shouldBe UnavailableReason.SOLD_OUT

        soldOn(roomTypeId, march1) shouldBe 0
        soldOn(roomTypeId, march1.plusDays(2)) shouldBe 0
        // 픽스처가 만든 기존 예약 1건만 남는다. 새 예약도 통보도 생기지 않았다
        reservations.count() shouldBe 1
        outbox.count() shouldBe 0
    }

    test("격자가 없는 날짜는 매진과 다른 사유로 거절된다") {
        // Given: 격자를 3/1 하루만 열어 두었는데 3박 예약이 들어온다
        val roomTypeId = InventoryFixture(dataSource).seedGrid(march1, days = 1, physicalTotal = 10)

        // When
        val result = inventoryService.reserve(command(roomTypeId))

        // Then: 매진은 정상 운영이고 격자 없음은 "재고를 열지 않았다" 는 운영 신호다.
        // 하나로 뭉치면 재고를 안 연 것이 매진으로 보고되어 아무도 알아채지 못한다
        val rejected = result.shouldBeInstanceOf<ReserveResult.Rejected>()
        rejected.unavailable.map { it.reason } shouldContainExactly listOf(
            UnavailableReason.NO_GRID,
            UnavailableReason.NO_GRID,
        )
        rejected.unavailable.map { it.stayDate } shouldContainExactly listOf(
            march1.plusDays(1),
            march1.plusDays(2),
        )
    }

    test("잔여보다 많이 요청하면 거절된다 — 남은 수가 사유에 실린다") {
        // Given: 물리 2 인 날짜
        val roomTypeId = InventoryFixture(dataSource).seedGrid(march1, days = 2, physicalTotal = 2)

        // When: 3객실 요청
        val result = inventoryService.reserve(
            command(roomTypeId, checkOut = march1.plusDays(1), roomCount = 3),
        )

        // Then: 남은 수가 없으면 호출부가 "몇 개까지 되는가" 를 다시 물어야 한다
        val rejected = result.shouldBeInstanceOf<ReserveResult.Rejected>()
        rejected.unavailable.first().remaining shouldBe 2
    }

    test("오버부킹 한도가 있으면 물리 재고를 넘겨 판다 — total 은 두 컬럼의 합이다") {
        // Given: 물리 1 + 한도 1
        val roomTypeId = InventoryFixture(dataSource)
            .seedGrid(march1, days = 2, physicalTotal = 1, overbookingLimit = 1)

        // When: 2객실
        val result = inventoryService.reserve(
            command(roomTypeId, checkOut = march1.plusDays(1), roomCount = 2),
        )

        // Then: 한도 0 이면 현재 동작과 수학적으로 동일하다는 것이 ADR-0007 의 주장이고,
        // 그 주장이 성립하려면 검사식이 두 컬럼의 합이어야 한다
        result.shouldBeInstanceOf<ReserveResult.Reserved>()
        soldOn(roomTypeId, march1) shouldBe 2
    }

    // ── Outbox ────────────────────────────────────────────────────────────
    test("통보가 날짜마다 하나씩, 예약과 같은 트랜잭션에 적힌다") {
        // Given / When: 3박 예약
        val roomTypeId = InventoryFixture(dataSource).seedGrid(march1, days = 4, physicalTotal = 10)
        inventoryService.reserve(command(roomTypeId))

        // Then: 채널 API 가 (룸타입, 날짜) 단위로 재고를 받으므로 날짜마다 하나다
        val events = outbox.findAll()
        events shouldHaveSize 3
        events.map { it.eventType }.toSet() shouldBe setOf("INVENTORY_CHANGED")
        events.all { it.status.name == "PENDING" } shouldBe true

        // 예약이 저장됐다는 것과 통보가 적혔다는 것이 한 커밋 안에 있다.
        // 나뉘면 "DB 는 바뀌었는데 통보가 안 나간" 상태가 생긴다 (절대 규칙 3)
        reservations.count() shouldBe 1
    }

    test("통보 본문은 발행 시점이 아니라 사건 시점의 재고다") {
        // Given: 물리 10 인 하루짜리 격자
        val roomTypeId = InventoryFixture(dataSource).seedGrid(march1, days = 2, physicalTotal = 10)

        // When: 2객실을 잡고, 그 뒤에 3객실을 더 잡는다
        inventoryService.reserve(command(roomTypeId, checkOut = march1.plusDays(1), roomCount = 2))
        inventoryService.reserve(command(roomTypeId, checkOut = march1.plusDays(1), roomCount = 3))

        // Then: 첫 통보는 remaining 8 로 남아 있어야 한다. 릴레이가 발행하면서
        // 재고를 다시 조회하면 두 통보가 같은 값(5)으로 나가고, 그것은
        // at-least-once 가 아니라 순서 없는 최신값 전송이다 (#4)
        // payload 를 문자열로 매칭하지 않는다. jsonb 는 키 순서를 바꾸고 공백을
        // 정규화해서 돌려주므로, 문자열 비교는 저장 형식이 조금만 달라져도 깨진다
        val remainings = outbox.findAll()
            .sortedBy { it.id }
            .map { objectMapper.readTree(it.payload)["remaining"].asInt() }
        remainings shouldContainExactly listOf(8, 5)
    }

    // ── 요청 검증 ─────────────────────────────────────────────────────────
    test("체크아웃이 체크인보다 앞서면 DB 에 닿기 전에 거부된다") {
        // Given / When / Then: INV-3 의 최종 방어선은 DB CHECK 지만,
        // 400 과 500 을 가르려면 이쪽에서 먼저 걸러야 한다
        val thrown = runCatching {
            ReserveCommand(
                roomTypeId = 1,
                checkIn = march1.plusDays(3),
                checkOut = march1,
                roomCount = 1,
                channel = "CHANNEL_A",
                channelReservationId = "x",
                guestName = "김손님",
            )
        }.exceptionOrNull()

        (thrown is IllegalArgumentException) shouldBe true
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
