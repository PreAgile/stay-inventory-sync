package dev.preagile.stayinventory.support

import dev.preagile.stayinventory.domain.ReservationStatus
import javax.sql.DataSource

/**
 * 시스템 전체가 언제나 참이어야 하는 등식 넷.
 *
 * 개별 시나리오가 아니라 **전체의 참**을 확인하므로, 테스트가 의도하지 않은
 * 경로로 만든 불일치도 잡힌다. 그래서 각 스펙이 아니라 훅이 부른다.
 *
 * 근거: `docs/01-domain-model.md` 「시스템 불변식」 · `docs/03-testing-strategy.md`
 */
object InventoryInvariants {

    /**
     * `INV-2` 가 세는 상태 목록을 **[ReservationStatus.OCCUPYING] 에서 만든다.**
     *
     * SQL 에 상태 이름을 손으로 적으면 집합의 단일 진실 원천이 둘이 된다. 실제로
     * 그 사고가 이 저장소에 있었다 — `CHECKED_IN` 이 빠져 있어 체크인 한 건으로
     * 불변식이 깨졌다(#25). 그때 고친 것은 문서였고, 코드가 생긴 지금은 이쪽이
     * 원본이어야 한다.
     */
    private val occupyingLiteral: String =
        ReservationStatus.OCCUPYING.joinToString(", ") { "'${it.name}'" }

    /**
     * 위반을 **전부 모아서** 던진다. 첫 번째에서 멈추면 한 번의 실행으로 얻는
     * 정보가 줄고, 원인이 하나인지 여럿인지 판단할 수 없다.
     */
    fun assertAll(dataSource: DataSource) {
        val violations = collect(dataSource)
        if (violations.isNotEmpty()) {
            throw AssertionError(
                "불변식 위반 ${violations.size}건\n" + violations.joinToString("\n") { "  - $it" },
            )
        }
    }

    fun collect(dataSource: DataSource): List<String> = dataSource.connection.use { conn ->
        buildList {
            // ── INV-1  0 <= sold <= total ────────────────────────────────
            // DB CHECK 와 중복이다. 그래도 둔다 -- 제약이 사라진 마이그레이션이
            // 들어와도 여기서 드러나야 하고, CHECK 는 그 마이그레이션 자신을 막지 못한다.
            addAll(
                conn.rows(
                    """
                    SELECT room_type_id, stay_date, sold, physical_total + overbooking_limit
                      FROM daily_inventory
                     WHERE sold < 0 OR sold > physical_total + overbooking_limit
                    """.trimIndent(),
                ) { rs ->
                    "INV-1 룸타입 ${rs.getLong(1)} ${rs.getString(2)}: " +
                        "sold=${rs.getInt(3)} total=${rs.getInt(4)}"
                },
            )

            // ── INV-2  sold == 점유 예약의 room_count 합 ──────────────────
            // 이 등식이 이 저장소가 하는 주장의 전부다. sold 가 실제보다 작으면
            // 이미 팔린 방을 또 팔고(오버부킹), 크면 있는 방을 못 판다(기회손실).
            addAll(
                conn.rows(
                    """
                    SELECT di.room_type_id, di.stay_date, di.sold, COALESCE(o.cnt, 0)
                      FROM daily_inventory di
                      LEFT JOIN LATERAL (
                           SELECT SUM(r.room_count) AS cnt
                             FROM reservation r
                            WHERE r.room_type_id = di.room_type_id
                              AND r.check_in    <= di.stay_date
                              AND r.check_out   >  di.stay_date
                              AND r.status IN ($occupyingLiteral)
                      ) o ON TRUE
                     WHERE di.sold <> COALESCE(o.cnt, 0)
                    """.trimIndent(),
                ) { rs ->
                    "INV-2 룸타입 ${rs.getLong(1)} ${rs.getString(2)}: " +
                        "sold=${rs.getInt(3)} 인데 점유 예약 합=${rs.getInt(4)}"
                },
            )

            // INV-2 의 반대 방향. 점유 중인 예약이 있는데 그 날짜의 재고 행이 아예 없다.
            //
            // 위 쿼리는 daily_inventory 를 기준으로 도니까 격자에 없는 날짜를 보지 못한다.
            // 그런데 격자 없이 확정된 예약은 **차감이 일어나지 않았다**는 뜻이고,
            // 그 방은 아무 제약 없이 다시 팔린다. 한쪽 방향만 보면 조용히 지나간다.
            addAll(
                conn.rows(
                    """
                    SELECT r.id, r.room_type_id, d.stay_date
                      FROM reservation r
                      CROSS JOIN LATERAL generate_series(
                           r.check_in, r.check_out - INTERVAL '1 day', INTERVAL '1 day'
                      ) AS d(stay_date)
                     WHERE r.status IN ($occupyingLiteral)
                       AND NOT EXISTS (
                           SELECT 1 FROM daily_inventory di
                            WHERE di.room_type_id = r.room_type_id
                              AND di.stay_date    = d.stay_date::date
                       )
                    """.trimIndent(),
                ) { rs ->
                    "INV-2 예약 ${rs.getLong(1)} 이 점유 중인데 " +
                        "룸타입 ${rs.getLong(2)} ${rs.getString(3)} 에 재고 행이 없다"
                },
            )

            // ── INV-3  check_in < check_out ──────────────────────────────
            addAll(
                conn.rows(
                    "SELECT id, check_in, check_out FROM reservation WHERE check_in >= check_out",
                ) { rs ->
                    "INV-3 예약 ${rs.getLong(1)}: ${rs.getString(2)} ~ ${rs.getString(3)}"
                },
            )

            // ── INV-4  sold + 유효 선점 <= total ──────────────────────────
            // 유효 선점은 두 조건을 모두 만족해야 한다. expires_at 만 보면 확정된
            // 예약이 남은 시간 동안 자기 방을 계속 붙잡아 품절을 만든다.
            addAll(
                conn.rows(
                    """
                    SELECT di.room_type_id, di.stay_date, di.sold,
                           COALESCE(h.cnt, 0), di.physical_total + di.overbooking_limit
                      FROM daily_inventory di
                      LEFT JOIN LATERAL (
                           SELECT SUM(ih.room_count) AS cnt
                             FROM inventory_hold ih
                            WHERE ih.room_type_id = di.room_type_id
                              AND ih.stay_date    = di.stay_date
                              AND ih.expires_at   > now()
                              AND ih.released_at IS NULL
                      ) h ON TRUE
                     WHERE di.sold + COALESCE(h.cnt, 0)
                           > di.physical_total + di.overbooking_limit
                    """.trimIndent(),
                ) { rs ->
                    "INV-4 룸타입 ${rs.getLong(1)} ${rs.getString(2)}: " +
                        "sold=${rs.getInt(3)} + 유효선점=${rs.getInt(4)} > total=${rs.getInt(5)}"
                },
            )
        }
    }

    /** `INV-2` 만 뺀 검사. 도메인 연산 없이 행을 직접 넣는 스펙이 쓴다 ([DirectRowSpec]). */
    fun assertExceptInv2(dataSource: DataSource) {
        val violations = collect(dataSource).filterNot { it.startsWith("INV-2") }
        if (violations.isNotEmpty()) {
            throw AssertionError(
                "불변식 위반 ${violations.size}건 (INV-2 면제)\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
    }

    private fun <T> java.sql.Connection.rows(sql: String, map: (java.sql.ResultSet) -> T): List<T> =
        createStatement().use { st ->
            st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }
}
