package dev.preagile.stayinventory

import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.sql.Connection
import javax.sql.DataSource

/**
 * V1 이 실제 PostgreSQL 에 올라갔는지, 그리고 **문서가 약속한 제약이 실제로
 * 존재하는지** 본다.
 *
 * 테이블이 만들어졌다는 것만 확인하면 부족하다. 이 프로젝트가 주장하는 것은
 * 스키마의 모양이 아니라 **DB 가 무엇을 막는가** 이고, 제약은 조용히 사라질 수 있다.
 * 그래서 각 항목을 "위반을 시도해서 거부되는가" 로 확인한다.
 */
@SpringBootTest
@Import(PostgresTestContainer::class)
class SchemaMigrationTest(
    private val dataSource: DataSource,
) : FunSpec({

    /** 제약 위반이 기대한 SQLSTATE 로 거부되는지 본다. 통과하면 그 자리가 뚫린 것이다. */
    fun Connection.expectRejected(sqlState: String, sql: String) {
        val rejected =
            try {
                createStatement().use { it.executeUpdate(sql) }
                false
            } catch (e: java.sql.SQLException) {
                e.sqlState shouldBe sqlState
                true
            }
        rejected shouldBe true
    }

    fun Connection.exec(sql: String) = createStatement().use { it.executeUpdate(sql) }

    fun Connection.count(sql: String): Int =
        createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getInt(1) } }

    // 테스트마다 빈 스키마에서 시작한다. 남은 데이터에 기대면 실행 순서에 의존하게 되고,
    // 그때 실패는 "어느 테스트가 무엇을 남겼는가" 를 찾는 일이 된다.
    // 커밋 04 의 불변식 훅이 붙을 자리도 여기다 (테스트 종료 시 검증).
    beforeTest {
        dataSource.connection.use { conn ->
            conn.exec(
                """
                TRUNCATE channel_policy, inventory_hold, inbound_message, outbox_event,
                         reservation, daily_inventory, room_type, property
                RESTART IDENTITY CASCADE
                """.trimIndent(),
            )
        }
    }

    // ── 스키마 형태 ────────────────────────────────────────────────────────
    test("테이블 8개가 정확히 이 이름으로 존재한다") {
        dataSource.connection.use { conn ->
            val tables = conn.createStatement().use { st ->
                st.executeQuery(
                    """
                    SELECT table_name FROM information_schema.tables
                     WHERE table_schema = 'public'
                       AND table_name <> 'flyway_schema_history'
                     ORDER BY table_name
                    """.trimIndent(),
                ).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
            tables shouldContainExactly listOf(
                "channel_policy", "daily_inventory", "inbound_message", "inventory_hold",
                "outbox_event", "property", "reservation", "room_type",
            )
        }
    }

    test("total 이라는 컬럼은 없다 — 계산값이다 (ADR-0007)") {
        dataSource.connection.use { conn ->
            // 이 검사가 실패하면 누군가 합산값을 컬럼으로 되살린 것이다.
            // 그러면 지표·경고·되돌림 셋을 동시에 잃는다.
            conn.count(
                """
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name = 'daily_inventory' AND column_name = 'total'
                """.trimIndent(),
            ) shouldBe 0

            conn.count(
                """
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name = 'daily_inventory'
                   AND column_name IN ('physical_total', 'overbooking_limit', 'sold')
                """.trimIndent(),
            ) shouldBe 3
        }
    }

    // ── 제약이 실제로 막는가 ───────────────────────────────────────────────
    test("INV-1 — sold 가 physical_total + overbooking_limit 를 넘으면 거부된다") {
        dataSource.connection.use { conn ->
            conn.exec("INSERT INTO property (id, name) VALUES (1, 'P')")
            conn.exec("INSERT INTO room_type (id, property_id, name, capacity) VALUES (1, 1, 'D', 2)")

            // 한도 0 이면 물리 재고까지만
            conn.exec(
                """
                INSERT INTO daily_inventory (room_type_id, stay_date, physical_total, sold)
                VALUES (1, '2026-03-01', 10, 10)
                """.trimIndent(),
            )
            // 23514 = check_violation
            conn.expectRejected(
                "23514",
                "UPDATE daily_inventory SET sold = 11 WHERE stay_date = '2026-03-01'",
            )
            conn.expectRejected(
                "23514",
                """
                INSERT INTO daily_inventory (room_type_id, stay_date, physical_total, sold)
                VALUES (1, '2026-03-02', 10, -1)
                """.trimIndent(),
            )

            // 한도를 열면 그만큼 허용된다 — 오버부킹은 정책이지 사고가 아니다
            conn.exec(
                """
                INSERT INTO daily_inventory (room_type_id, stay_date, physical_total, overbooking_limit, sold)
                VALUES (1, '2026-03-03', 10, 2, 12)
                """.trimIndent(),
            )
            conn.expectRejected(
                "23514",
                "UPDATE daily_inventory SET sold = 13 WHERE stay_date = '2026-03-03'",
            )
        }
    }

    test("INV-3 — check_in 이 check_out 보다 뒤면 거부된다") {
        dataSource.connection.use { conn ->
            conn.exec("INSERT INTO property (id, name) VALUES (2, 'P2')")
            conn.exec("INSERT INTO room_type (id, property_id, name, capacity) VALUES (2, 2, 'D', 2)")
            conn.expectRejected(
                "23514",
                """
                INSERT INTO reservation
                       (room_type_id, check_in, check_out, status, channel, channel_reservation_id, guest_name)
                VALUES (2, '2026-03-05', '2026-03-05', 'CONFIRMED', 'DIRECT', 'R-SAME', 'G')
                """.trimIndent(),
            )
        }
    }

    test("room_count 가 0 이하면 거부된다 — 음수는 INV-4 를 통과하며 INV-1 을 깬다") {
        dataSource.connection.use { conn ->
            conn.exec("INSERT INTO property (id, name) VALUES (3, 'P3')")
            conn.exec("INSERT INTO room_type (id, property_id, name, capacity) VALUES (3, 3, 'D', 2)")
            conn.expectRejected(
                "23514",
                """
                INSERT INTO reservation
                       (room_type_id, check_in, check_out, status, room_count,
                        channel, channel_reservation_id, guest_name)
                VALUES (3, '2026-03-01', '2026-03-02', 'CONFIRMED', -5, 'DIRECT', 'R-NEG', 'G')
                """.trimIndent(),
            )
        }
    }

    test("상태 9개 밖의 값은 거부된다 — 허용 목록이다") {
        dataSource.connection.use { conn ->
            conn.exec("INSERT INTO property (id, name) VALUES (4, 'P4')")
            conn.exec("INSERT INTO room_type (id, property_id, name, capacity) VALUES (4, 4, 'D', 2)")
            conn.expectRejected(
                "23514",
                """
                INSERT INTO reservation
                       (room_type_id, check_in, check_out, status, channel, channel_reservation_id, guest_name)
                VALUES (4, '2026-03-01', '2026-03-02', 'REFUNDED', 'DIRECT', 'R-BAD', 'G')
                """.trimIndent(),
            )
        }
    }

    test("중복 웹훅 — 같은 (channel, channel_reservation_id) 두 번은 거부된다") {
        dataSource.connection.use { conn ->
            conn.exec("INSERT INTO property (id, name) VALUES (5, 'P5')")
            conn.exec("INSERT INTO room_type (id, property_id, name, capacity) VALUES (5, 5, 'D', 2)")
            val insert = """
                INSERT INTO reservation
                       (room_type_id, check_in, check_out, status, channel, channel_reservation_id, guest_name)
                VALUES (5, '2026-03-01', '2026-03-02', 'CONFIRMED', 'YANOLJA', 'BK-1', 'G')
            """.trimIndent()
            conn.exec(insert)
            conn.expectRejected("23505", insert) // unique_violation
        }
    }

    test("Inbox 멱등 — 순서키가 NULL 이어도 같은 알림은 한 번만 들어간다") {
        dataSource.connection.use { conn ->
            val insert = """
                INSERT INTO inbound_message (channel, kind, external_id, payload)
                VALUES ('YANOLJA', 'BOOKING', 'MSG-1', '{}'::jsonb)
            """.trimIndent()
            conn.exec(insert)
            // NULLS NOT DISTINCT 가 빠지면 이 두 번째가 통과해 이 테스트가 실패한다.
            conn.expectRejected("23505", insert)
            conn.count("SELECT count(*) FROM inbound_message WHERE external_id = 'MSG-1'") shouldBe 1
        }
    }

    test("channel_policy — CLOSED 에 값을 주거나 CAP 에 값을 빼면 거부된다") {
        dataSource.connection.use { conn ->
            conn.exec("INSERT INTO property (id, name) VALUES (6, 'P6')")
            conn.exec("INSERT INTO room_type (id, property_id, name, capacity) VALUES (6, 6, 'D', 2)")

            conn.expectRejected(
                "23514",
                """
                INSERT INTO channel_policy (room_type_id, stay_date, channel, kind, value, source)
                VALUES (6, '2026-03-01', 'YANOLJA', 'CLOSED', 3, 'OURS')
                """.trimIndent(),
            )
            conn.expectRejected(
                "23514",
                """
                INSERT INTO channel_policy (room_type_id, stay_date, channel, kind, value, source)
                VALUES (6, '2026-03-01', 'YANOLJA', 'CAP', NULL, 'OURS')
                """.trimIndent(),
            )
        }
    }

    test("channel_policy 는 재고 행이 없는 미래 날짜에도 설 수 있다 — FK 를 걸지 않은 이유") {
        dataSource.connection.use { conn ->
            conn.exec("INSERT INTO property (id, name) VALUES (7, 'P7')")
            conn.exec("INSERT INTO room_type (id, property_id, name, capacity) VALUES (7, 7, 'D', 2)")

            // daily_inventory 에 2026-12-25 행이 없다. 그래도 노출 상한은 미리 선다.
            conn.count(
                "SELECT count(*) FROM daily_inventory WHERE room_type_id = 7 AND stay_date = '2026-12-25'",
            ) shouldBe 0

            conn.exec(
                """
                INSERT INTO channel_policy (room_type_id, stay_date, channel, kind, value, source)
                VALUES (7, '2026-12-25', 'YANOLJA', 'CAP', 2, 'OURS')
                """.trimIndent(),
            )
            conn.count(
                "SELECT count(*) FROM channel_policy WHERE room_type_id = 7 AND stay_date = '2026-12-25'",
            ) shouldBe 1

            // 알림 하나가 kind 여러 개를 실어 오는 경우 — PK 에 kind 가 있어야 둘 다 선다
            conn.exec(
                """
                INSERT INTO channel_policy (room_type_id, stay_date, channel, kind, value, source)
                VALUES (7, '2026-12-25', 'YANOLJA', 'CLOSED', NULL, 'CHANNEL')
                """.trimIndent(),
            )
            conn.count(
                "SELECT count(*) FROM channel_policy WHERE room_type_id = 7 AND stay_date = '2026-12-25'",
            ) shouldBe 2
        }
    }

    test("inventory_hold 는 없는 재고 행을 선점할 수 없다 — 이쪽은 FK 를 건다") {
        dataSource.connection.use { conn ->
            conn.exec("INSERT INTO property (id, name) VALUES (8, 'P8')")
            conn.exec("INSERT INTO room_type (id, property_id, name, capacity) VALUES (8, 8, 'D', 2)")
            conn.exec(
                """
                INSERT INTO reservation
                       (id, room_type_id, check_in, check_out, status, channel, channel_reservation_id, guest_name)
                VALUES (900, 8, '2026-03-01', '2026-03-02', 'HELD', 'DIRECT', 'R-HOLD', 'G')
                """.trimIndent(),
            )
            // 23503 = foreign_key_violation
            conn.expectRejected(
                "23503",
                """
                INSERT INTO inventory_hold (room_type_id, stay_date, reservation_id, room_count, expires_at)
                VALUES (8, '2026-03-01', 900, 1, now() + interval '10 minutes')
                """.trimIndent(),
            )
        }
    }

    test("유효 선점 부분 인덱스가 released_at IS NULL 조건으로 존재한다") {
        dataSource.connection.use { conn ->
            // 인덱스 정의에서 조건이 사라지면 해제된 선점까지 인덱스에 남는다.
            val hasPartial = conn.count(
                """
                SELECT count(*) FROM pg_indexes
                 WHERE tablename = 'inventory_hold'
                   AND indexname = 'idx_inventory_hold_active'
                   AND indexdef ILIKE '%released_at IS NULL%'
                """.trimIndent(),
            )
            hasPartial shouldBe 1
        }
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
