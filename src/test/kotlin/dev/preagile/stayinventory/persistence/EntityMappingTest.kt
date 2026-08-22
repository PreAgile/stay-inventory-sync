package dev.preagile.stayinventory.persistence

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.domain.BookingMode
import dev.preagile.stayinventory.domain.ChannelPolicyKind
import dev.preagile.stayinventory.domain.ChannelPolicySource
import dev.preagile.stayinventory.domain.InboundKind
import dev.preagile.stayinventory.domain.InboundStatus
import dev.preagile.stayinventory.domain.OutboxStatus
import dev.preagile.stayinventory.domain.ReservationStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.sql.DataSource

/**
 * 엔티티가 `V1__init.sql` 의 행과 **왕복**하는지 본다.
 *
 * Hibernate 의 `ddl-auto: validate` 는 컨텍스트가 뜰 때 컬럼의 존재와 타입을
 * 대조하므로 이름을 틀리면 부팅이 죽는다. 그것이 잡지 못하는 것을 여기서 잡는다 --
 * enum 이 문자열로 나가는지, `jsonb` 컬럼이 문자열로 왕복하는지, `timestamptz` 가
 * 시각을 잃지 않는지, 복합 키가 그대로 돌아오는지.
 *
 * **`equals`/`hashCode` 도 여기서 고정한다.** 절대 규칙 1 이 금지하는 것은
 * `data class` 이고, 그 근거는 영속 전후로 해시가 달라진다는 것이다. 근거가
 * 실제로 지켜지는지는 영속 전후를 모두 만져 봐야 확인된다.
 */
@SpringBootTest
@Import(PostgresTestContainer::class)
class EntityMappingTest(
    private val dataSource: DataSource,
    private val properties: PropertyRepository,
    private val roomTypes: RoomTypeRepository,
    private val inventories: DailyInventoryRepository,
    private val reservations: ReservationRepository,
    private val holds: InventoryHoldRepository,
    private val outbox: OutboxEventRepository,
    private val inbound: InboundMessageRepository,
    private val policies: ChannelPolicyRepository,
) : FunSpec({

    val stayDate = LocalDate.of(2026, 3, 1)

    beforeTest {
        dataSource.connection.use { conn ->
            conn.createStatement().use {
                it.executeUpdate(
                    """
                    TRUNCATE channel_policy, inventory_hold, inbound_message, outbox_event,
                             reservation, daily_inventory, room_type, property
                    RESTART IDENTITY CASCADE
                    """.trimIndent(),
                )
            }
        }
    }

    /** 재고 격자까지 깔아 둔다. 선점·정책이 그 위에 선다. */
    fun seedGrid(physicalTotal: Int = 10): Pair<Long, DailyInventory> {
        val property = properties.save(Property(name = "스테이 A"))
        val roomType = roomTypes.save(
            RoomType(
                propertyId = property.id!!,
                name = "디럭스",
                capacity = 2,
                bookingMode = BookingMode.ON_REQUEST,
            ),
        )
        val inventory = inventories.save(
            DailyInventory(
                id = DailyInventoryId(roomType.id!!, stayDate),
                physicalTotal = physicalTotal,
            ),
        )
        return roomType.id!! to inventory
    }

    fun seedReservation(roomTypeId: Long, roomCount: Int = 2): Reservation =
        reservations.save(
            Reservation(
                roomTypeId = roomTypeId,
                checkIn = stayDate,
                checkOut = stayDate.plusDays(3),
                status = ReservationStatus.HELD,
                roomCount = roomCount,
                channel = "CHANNEL_A",
                channelReservationId = "HMABC123",
                guestName = "김손님",
            ),
        )

    // ── 왕복 ──────────────────────────────────────────────────────────────
    test("숙소와 룸타입이 왕복한다 — booking_mode 가 문자열로 나간다") {
        // Given / When
        val (roomTypeId, _) = seedGrid()

        // Then: enum 이 서수로 저장되면 DB CHECK 가 23514 로 거부한다.
        // 통과했다는 사실 자체가 EnumType.STRING 의 증거다.
        val found = roomTypes.findById(roomTypeId).orElseThrow()
        found.bookingMode shouldBe BookingMode.ON_REQUEST
        found.capacity shouldBe 2

        properties.findById(found.propertyId).orElseThrow().timezone shouldBe "Asia/Seoul"
    }

    test("재고 격자가 복합 키로 왕복하고 total 은 계산값으로 나온다") {
        // Given: 물리 10 + 오버부킹 2
        val (roomTypeId, _) = seedGrid()
        inventories.save(
            DailyInventory(
                id = DailyInventoryId(roomTypeId, stayDate),
                physicalTotal = 10,
                overbookingLimit = 2,
                sold = 3,
            ),
        )

        // When
        val found = inventories.findById(DailyInventoryId(roomTypeId, stayDate)).orElseThrow()

        // Then: total 컬럼은 없다. 두 컬럼의 합이다 (ADR-0007)
        found.total shouldBe 12
        found.remaining shouldBe 9
        found.stayDate shouldBe stayDate
    }

    test("예약이 왕복하고 반개구간 날짜 목록이 체크아웃 당일을 빼고 나온다") {
        // Given: 3/1 입실 3/4 퇴실
        val (roomTypeId, _) = seedGrid()
        val saved = seedReservation(roomTypeId)

        // When
        val found = reservations.findById(saved.id!!).orElseThrow()

        // Then: 절대 규칙 6. 3/4 를 차감하면 그날 방 하나가 영영 안 팔린다
        found.stayDates() shouldContainExactly listOf(
            LocalDate.of(2026, 3, 1),
            LocalDate.of(2026, 3, 2),
            LocalDate.of(2026, 3, 3),
        )
        found.status shouldBe ReservationStatus.HELD
        found.roomCount shouldBe 2
    }

    test("날짜 목록은 오름차순이다 — 락 획득 순서가 여기서 나온다") {
        // Given: 여러 날에 걸친 예약
        val (roomTypeId, _) = seedGrid()
        val reservation = seedReservation(roomTypeId)

        // Then: 정렬이 이 목록에 이미 들어 있어야 호출부가 잊을 수 없다.
        // 호출부에서 sorted() 를 부르는 구조면 그 한 줄이 빠질 자리가 생기고,
        // 빠지면 T2 가 데드락으로 실패한다 (절대 규칙 2)
        val dates = reservation.stayDates()
        dates shouldBe dates.sorted()
    }

    test("선점이 왕복하고 유효 판정이 두 조건을 모두 본다") {
        // Given: 예약 하나에 선점 하나
        val (roomTypeId, _) = seedGrid()
        val reservation = seedReservation(roomTypeId)
        val now = Instant.now()
        val saved = holds.save(
            InventoryHold(
                roomTypeId = roomTypeId,
                stayDate = stayDate,
                reservationId = reservation.id!!,
                roomCount = reservation.roomCount,
                expiresAt = now.plus(10, ChronoUnit.MINUTES),
            ),
        )

        // When
        val found = holds.findById(saved.id!!).orElseThrow()

        // Then: 살아 있다
        found.isActiveAt(now) shouldBe true
        // 만료 뒤에는 죽는다 — 시간 조건만으로 판정된다 (마킹을 기다리지 않는다)
        found.isActiveAt(now.plus(11, ChronoUnit.MINUTES)) shouldBe false

        // 해제되면 만료 전이어도 죽는다. expires_at 만 보면 확정된 예약이
        // 남은 시간 동안 자기 방을 계속 붙잡는다
        found.releasedAt = now
        holds.save(found)
        holds.findById(saved.id!!).orElseThrow().isActiveAt(now) shouldBe false
    }

    test("Outbox 페이로드가 jsonb 로 왕복한다") {
        // Given: 발행 대기 이벤트
        val saved = outbox.save(
            OutboxEvent(
                aggregateType = "RESERVATION",
                aggregateId = 42L,
                eventType = "INVENTORY_CHANGED",
                payload = """{"roomTypeId":1,"stayDate":"2026-03-01","remaining":7}""",
            ),
        )

        // When
        val found = outbox.findById(saved.id!!).orElseThrow()

        // Then: 문자열이 jsonb 로 들어갔다가 그대로 돌아온다.
        // 매핑이 틀리면 validate 가 부팅에서 죽으므로 여기 도달한 것 자체가 증거다
        found.status shouldBe OutboxStatus.PENDING
        found.retryCount shouldBe 0
        found.publishedAt shouldBe null

        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT payload->>'remaining' FROM outbox_event WHERE id = ${saved.id}",
                ).use { rs ->
                    rs.next()
                    // jsonb 연산자가 먹는다는 것은 문자열이 아니라 jsonb 로 들어갔다는 뜻이다
                    rs.getString(1) shouldBe "7"
                }
            }
        }
    }

    test("인바운드 알림이 순서키 없이도 저장된다 — NULL 을 빈 문자열로 뭉개지 않는다") {
        // Given: 순서키를 주지 않는 채널
        val saved = inbound.save(
            InboundMessage(
                channel = "CHANNEL_B",
                kind = InboundKind.BOOKING,
                externalId = "BDC-9001",
                sequenceKey = null,
                payload = """{"event":"reservation.created"}""",
            ),
        )

        // Then: NULL 이 그대로 남아야 #9 의 순서 역전 판정이 "주지 않았다" 를 읽는다
        val found = inbound.findById(saved.id!!).orElseThrow()
        found.sequenceKey shouldBe null
        found.status shouldBe InboundStatus.PENDING
        found.processedAt shouldBe null
    }

    test("정책 장부가 네 값 복합 키로 왕복한다") {
        // Given: 같은 룸타입·날짜·채널에 kind 가 다른 두 규칙
        val (roomTypeId, _) = seedGrid()
        policies.save(
            ChannelPolicy(
                id = ChannelPolicyId(roomTypeId, stayDate, "CHANNEL_A", ChannelPolicyKind.CAP),
                value = 5,
                source = ChannelPolicySource.OURS,
            ),
        )
        policies.save(
            ChannelPolicy(
                id = ChannelPolicyId(roomTypeId, stayDate, "CHANNEL_A", ChannelPolicyKind.CLOSED),
                value = null,
                source = ChannelPolicySource.CHANNEL,
            ),
        )

        // Then: kind 가 키에 없으면 둘째가 첫째를 덮어써 상한이 사라진다
        policies.count() shouldBe 2
        val cap = policies
            .findById(ChannelPolicyId(roomTypeId, stayDate, "CHANNEL_A", ChannelPolicyKind.CAP))
            .orElseThrow()
        cap.value shouldBe 5
        cap.source shouldBe ChannelPolicySource.OURS
    }

    test("재고 행이 없는 미래 날짜에도 정책을 걸 수 있다 — FK 를 걸지 않은 이유") {
        // Given: 격자는 3/1 까지만 있다
        val (roomTypeId, _) = seedGrid()

        // When: 아직 재고 행이 없는 6/1 에 상한을 미리 건다
        policies.save(
            ChannelPolicy(
                id = ChannelPolicyId(
                    roomTypeId,
                    LocalDate.of(2026, 6, 1),
                    "CHANNEL_C",
                    ChannelPolicyKind.CAP,
                ),
                value = 3,
                source = ChannelPolicySource.OURS,
            ),
        )

        // Then: FK 를 걸었다면 여기서 23503 으로 죽는다. 캡형 운영(#36)이 요구하는 동작이다
        policies.count() shouldBe 1
    }

    // ── 동일성 ────────────────────────────────────────────────────────────
    test("엔티티 해시는 영속 전후로 바뀌지 않는다 — data class 를 쓰지 않는 이유") {
        // Given: 아직 id 가 없는 비영속 엔티티
        val transient = Property(name = "스테이 C")
        val before = transient.hashCode()

        // When: 저장되어 id 가 생긴다
        val persisted = properties.save(transient)

        // Then: 해시가 그대로다. 값 기반 해시였다면 여기서 바뀌고,
        // 저장 전에 HashSet 에 넣어 둔 엔티티를 저장 후에 찾지 못한다
        persisted.id.shouldNotBeNull()
        persisted.hashCode() shouldBe before
    }

    test("id 가 같으면 같은 엔티티다 — 다른 필드가 달라도") {
        // Given: 같은 행을 두 번 읽는다
        val saved = properties.save(Property(name = "스테이 B"))
        val a = properties.findById(saved.id!!).orElseThrow()
        val b = Property(name = "이름이 달라도", id = saved.id)

        // Then
        (a == b) shouldBe true
    }

    test("id 가 없는 두 엔티티는 서로 다르다 — 값이 같아도") {
        // Given: 저장 전 엔티티 둘
        val a = Property(name = "같은 이름")
        val b = Property(name = "같은 이름")

        // Then: 값 기반 equals 였다면 여기서 같다고 나오고, 저장 전 목록에서
        // 서로 다른 두 숙소가 하나로 접힌다
        a shouldNotBe b
        (a == a) shouldBe true
    }
})
