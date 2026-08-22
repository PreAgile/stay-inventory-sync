package dev.preagile.stayinventory.support

import dev.preagile.stayinventory.PostgresTestContainer
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.sql.Connection
import javax.sql.DataSource

/**
 * 불변식 검사가 **실제로 위반을 잡는지** 본다.
 *
 * 통과하는 검사는 아무것도 증명하지 않는다. 네 등식을 각각 깨뜨려서 그때마다
 * 그 등식이 (그리고 그것만이) 보고되는지 확인한다.
 *
 * `INV-1` 과 `INV-3` 은 DB `CHECK` 가 이미 막고 있어 정상 경로로는 깨뜨릴 수 없다.
 * **제약을 잠시 떼고 깨뜨린다.** 이것이 "DB 가 막는데 왜 애플리케이션에서도 세는가"
 * 에 대한 답이기도 하다 -- 제약을 지우는 마이그레이션이 들어오면 DB 는 자기 자신을
 * 막지 못하고, 그때 남는 방어선이 이 검사다.
 *
 * 각 테스트는 **끝에서 스스로 치운다.** 치우지 않으면 이 스펙이 만든 위반을
 * 훅이 다시 잡아 다음 테스트의 실패로 나타난다.
 */
@SpringBootTest
@Import(PostgresTestContainer::class)
class InvariantDetectionTest(
    private val dataSource: DataSource,
) : FunSpec({

    fun Connection.exec(sql: String) = createStatement().use { it.executeUpdate(sql) }

    fun wipe() = dataSource.connection.use {
        it.exec(
            """
            TRUNCATE channel_policy, inventory_hold, inbound_message, outbox_event,
                     reservation, daily_inventory, room_type, property
            RESTART IDENTITY CASCADE
            """.trimIndent(),
        )
    }

    /** 룸타입 하나와 3/1 격자 하나를 깔고 룸타입 id 를 준다. */
    fun seed(conn: Connection, physicalTotal: Int = 10, sold: Int = 0): Long {
        conn.exec("INSERT INTO property (name) VALUES ('어반스테이 성수')")
        conn.exec(
            "INSERT INTO room_type (property_id, name, capacity) " +
                "VALUES (1, '디럭스', 2)",
        )
        conn.exec(
            "INSERT INTO daily_inventory (room_type_id, stay_date, physical_total, sold) " +
                "VALUES (1, DATE '2026-03-01', $physicalTotal, $sold)",
        )
        return 1L
    }

    beforeTest { wipe() }

    test("INV-1 — sold 가 total 을 넘으면 잡힌다 (CHECK 를 떼고 확인한다)") {
        dataSource.connection.use { conn ->
            // Given: 제약이 사라진 스키마. 제약을 지우는 마이그레이션이 들어온 상황이다
            seed(conn)
            conn.exec(
                "ALTER TABLE daily_inventory DROP CONSTRAINT daily_inventory_sold_within_total",
            )

            // When: 물리 10 인 날짜에 11 이 팔린 것으로 만든다
            conn.exec("UPDATE daily_inventory SET sold = 11 WHERE room_type_id = 1")

            // Then: DB 는 이제 막지 않는다. 검사가 잡아야 한다
            val violations = InventoryInvariants.collect(dataSource)
            violations.filter { it.startsWith("INV-1") } shouldHaveSize 1
            violations.first { it.startsWith("INV-1") } shouldContain "sold=11 total=10"

            // 치운다 — 제약을 되돌리고 데이터를 지운다
            conn.exec("UPDATE daily_inventory SET sold = 0 WHERE room_type_id = 1")
            conn.exec(
                """
                ALTER TABLE daily_inventory ADD CONSTRAINT daily_inventory_sold_within_total
                    CHECK (sold >= 0 AND sold <= physical_total + overbooking_limit)
                """.trimIndent(),
            )
        }
        wipe()
    }

    test("INV-2 — 카운터가 예약 사실보다 크면 잡힌다") {
        dataSource.connection.use { conn ->
            // Given: 예약이 하나도 없는데 sold 가 2 인 격자
            seed(conn, sold = 2)

            // When / Then: 이 어긋남이 곧 "있는 방을 못 파는" 상태다
            val violations = InventoryInvariants.collect(dataSource)
            violations.filter { it.startsWith("INV-2") } shouldHaveSize 1
            violations.first { it.startsWith("INV-2") } shouldContain "sold=2 인데 점유 예약 합=0"
        }
        wipe()
    }

    test("INV-2 — 카운터가 예약 사실보다 작으면 잡힌다 (오버부킹 방향)") {
        dataSource.connection.use { conn ->
            // Given: 확정 예약 2객실이 있는데 sold 는 0
            seed(conn, sold = 0)
            conn.exec(
                """
                INSERT INTO reservation
                       (room_type_id, check_in, check_out, status, room_count,
                        channel, channel_reservation_id, guest_name)
                VALUES (1, DATE '2026-03-01', DATE '2026-03-02', 'CONFIRMED', 2,
                        'AIRBNB', 'HM-1', '김손님')
                """.trimIndent(),
            )

            // Then: 이 방향이 오버부킹을 만든다. 이미 팔린 방을 또 판다
            InventoryInvariants.collect(dataSource)
                .first { it.startsWith("INV-2") } shouldContain "sold=0 인데 점유 예약 합=2"
        }
        wipe()
    }

    test("INV-2 — 점유 예약이 있는데 그 날짜의 재고 행이 없으면 잡힌다") {
        dataSource.connection.use { conn ->
            // Given: 격자는 3/1 하루뿐인데 예약은 3/1~3/3 이다
            seed(conn, sold = 1)
            conn.exec(
                """
                INSERT INTO reservation
                       (room_type_id, check_in, check_out, status, room_count,
                        channel, channel_reservation_id, guest_name)
                VALUES (1, DATE '2026-03-01', DATE '2026-03-03', 'CONFIRMED', 1,
                        'AIRBNB', 'HM-2', '이손님')
                """.trimIndent(),
            )

            // Then: 3/2 는 차감이 일어난 적이 없다. 격자를 기준으로만 돌면
            // 이 날짜는 조회 대상이 아니라서 조용히 지나간다
            InventoryInvariants.collect(dataSource)
                .first { it.contains("재고 행이 없다") } shouldContain "2026-03-02"
        }
        wipe()
    }

    test("INV-3 — 체크인이 체크아웃보다 뒤면 잡힌다 (CHECK 를 떼고 확인한다)") {
        dataSource.connection.use { conn ->
            // Given: 제약이 사라진 스키마
            seed(conn)
            conn.exec("ALTER TABLE reservation DROP CONSTRAINT reservation_stay_range_valid")

            // When: 역전된 기간을 넣는다. 상태는 점유가 아닌 것으로 두어
            // INV-2 가 함께 뜨지 않게 한다 -- 검사 하나가 다른 검사를 가리면 안 된다
            conn.exec(
                """
                INSERT INTO reservation
                       (room_type_id, check_in, check_out, status, room_count,
                        channel, channel_reservation_id, guest_name)
                VALUES (1, DATE '2026-03-05', DATE '2026-03-01', 'CANCELED', 1,
                        'AIRBNB', 'HM-3', '박손님')
                """.trimIndent(),
            )

            // Then
            val violations = InventoryInvariants.collect(dataSource)
            violations.filter { it.startsWith("INV-3") } shouldHaveSize 1

            // 치운다
            conn.exec("DELETE FROM reservation")
            conn.exec(
                "ALTER TABLE reservation ADD CONSTRAINT reservation_stay_range_valid " +
                    "CHECK (check_in < check_out)",
            )
        }
        wipe()
    }

    test("INV-4 — 과선점은 잡힌다. DB 에 이것을 막는 제약은 없다") {
        dataSource.connection.use { conn ->
            // Given: 물리 2 인 날짜에 이미 1 이 팔렸다
            seed(conn, physicalTotal = 2, sold = 1)
            conn.exec(
                """
                INSERT INTO reservation
                       (room_type_id, check_in, check_out, status, room_count,
                        channel, channel_reservation_id, guest_name)
                VALUES (1, DATE '2026-03-01', DATE '2026-03-02', 'CONFIRMED', 1,
                        'AIRBNB', 'HM-4', '최손님'),
                       (1, DATE '2026-03-01', DATE '2026-03-02', 'HELD', 2,
                        'AIRBNB', 'HM-5', '정손님')
                """.trimIndent(),
            )

            // When: 남은 1 자리에 2객실 선점이 들어간다
            conn.exec(
                """
                INSERT INTO inventory_hold
                       (room_type_id, stay_date, reservation_id, room_count, expires_at)
                VALUES (1, DATE '2026-03-01', 2, 2, now() + INTERVAL '10 minutes')
                """.trimIndent(),
            )

            // Then: 1 + 2 > 2. 제약이 없으므로 이 검사만이 방어선이다
            InventoryInvariants.collect(dataSource)
                .first { it.startsWith("INV-4") } shouldContain "sold=1 + 유효선점=2 > total=2"
        }
        wipe()
    }

    test("만료·해제된 선점은 INV-4 에 세지 않는다 — 두 조건 모두가 필요하다") {
        dataSource.connection.use { conn ->
            // Given: 같은 과선점 상황인데 선점 하나는 만료, 하나는 해제됐다
            seed(conn, physicalTotal = 2, sold = 1)
            conn.exec(
                """
                INSERT INTO reservation
                       (room_type_id, check_in, check_out, status, room_count,
                        channel, channel_reservation_id, guest_name)
                VALUES (1, DATE '2026-03-01', DATE '2026-03-02', 'CONFIRMED', 1,
                        'AIRBNB', 'HM-6', '한손님'),
                       (1, DATE '2026-03-01', DATE '2026-03-02', 'EXPIRED', 2,
                        'AIRBNB', 'HM-7', '조손님'),
                       (1, DATE '2026-03-01', DATE '2026-03-02', 'CONFIRMED', 2,
                        'AIRBNB', 'HM-8', '윤손님')
                """.trimIndent(),
            )
            conn.exec(
                """
                INSERT INTO inventory_hold
                       (room_type_id, stay_date, reservation_id, room_count,
                        expires_at, released_at)
                VALUES (1, DATE '2026-03-01', 2, 2, now() - INTERVAL '1 minute', NULL),
                       (1, DATE '2026-03-01', 3, 2, now() + INTERVAL '10 minutes', now())
                """.trimIndent(),
            )

            // Then: INV-4 는 뜨지 않는다. 만료만 보거나 해제만 보면 하나를 놓친다
            InventoryInvariants.collect(dataSource)
                .none { it.startsWith("INV-4") } shouldBe true
        }
        wipe()
    }

    test("정합한 상태에서는 아무것도 보고하지 않는다") {
        dataSource.connection.use { conn ->
            // Given: 확정 예약 1건과 그만큼 차감된 격자
            seed(conn, physicalTotal = 10, sold = 2)
            conn.exec(
                """
                INSERT INTO reservation
                       (room_type_id, check_in, check_out, status, room_count,
                        channel, channel_reservation_id, guest_name)
                VALUES (1, DATE '2026-03-01', DATE '2026-03-02', 'CONFIRMED', 2,
                        'AIRBNB', 'HM-9', '서손님')
                """.trimIndent(),
            )

            // Then: 검사가 정상 상태를 위반으로 읽으면 훅이 상시 빨간불이 되고,
            // 상시 빨간불은 검사가 아니다
            InventoryInvariants.collect(dataSource) shouldHaveSize 0
        }
        wipe()
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
