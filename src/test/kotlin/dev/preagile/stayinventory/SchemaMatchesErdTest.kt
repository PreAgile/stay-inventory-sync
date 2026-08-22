package dev.preagile.stayinventory

import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.io.File
import javax.sql.DataSource

/**
 * ERD 와 실제 스키마가 같은 것을 말하는지 본다. `#2` 의 완료 게이트다.
 *
 * 이 프로젝트에서 **이미 한 번 일어난 사고**를 막는다 — 설계 결정이 테이블 둘을
 * 추가했는데 ERD 는 6개에 멈춰 있었다(PR #42). 사람이 두 곳을 맞추는 규율에
 * 기대면 다시 갈라진다.
 *
 * 이름만 맞추면 부족하다. `#2` 의 완료 기준은 **컬럼명 · 타입 · 키**이고,
 * 이름만 보는 게이트는 타입을 바꾸거나 PK 를 지워도 통과한다. 넷을 본다.
 *
 * **FK 는 ERD 파싱으로 판정하지 않는다.** 이 스키마에는 의도적으로 FK 를 두지
 * 않은 관계가 있고(ERD 에서 점선), ERD 의 `FK` 마커는 그 구분을 표현하지 못한다.
 * 그래서 문서화된 FK 정책을 **명시 목록**으로 고정하고, 없어야 하는 FK 가 생기는
 * 것까지 잡는다.
 */
@SpringBootTest
@Import(PostgresTestContainer::class)
class SchemaMatchesErdTest(
    private val dataSource: DataSource,
) : FunSpec({

    val repoRoot = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    /** ERD 표기 -> PostgreSQL 의 information_schema.data_type */
    val typeMap = mapOf(
        "bigint" to "bigint",
        "int" to "integer",
        "varchar" to "character varying",
        "date" to "date",
        "timestamptz" to "timestamp with time zone",
        "jsonb" to "jsonb",
    )

    data class ErdColumn(val name: String, val type: String, val isPk: Boolean)

    val erd: Map<String, List<ErdColumn>> = run {
        val doc = File(repoRoot, "docs/01-domain-model.md").readText()
        val block = Regex("```mermaid\\n(erDiagram.*?)```", RegexOption.DOT_MATCHES_ALL)
            .find(doc)!!.groupValues[1]
        Regex("^ {4}([A-Z_]+) \\{\\n(.*?)^ {4}\\}", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE))
            .findAll(block)
            .associate { m ->
                m.groupValues[1].lowercase() to m.groupValues[2].trim().lines().mapNotNull { line ->
                    // 주석(따옴표) 앞까지만 본다. 주석 안의 PK/FK 문자열에 속지 않기 위해서다.
                    val tokens = line.substringBefore('"').trim().split(" ").filter(String::isNotBlank)
                    if (tokens.size < 2) null
                    else ErdColumn(tokens[1], tokens[0], tokens.drop(2).contains("PK"))
                }
            }
    }

    fun query(sql: String): List<List<String?>> =
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    val n = rs.metaData.columnCount
                    buildList { while (rs.next()) add((1..n).map { rs.getString(it) }) }
                }
            }
        }

    // ── ① 테이블 집합 ──────────────────────────────────────────────────────
    test("ERD 와 실제 스키마가 같은 테이블 집합을 말한다") {
        val actual = query(
            """
            SELECT table_name FROM information_schema.tables
             WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
            """.trimIndent(),
        ).map { it[0]!! }.toSet()

        ((erd.keys - actual).map { "ERD 에만: $it" } +
            (actual - erd.keys).map { "스키마에만: $it" }).shouldBeEmpty()
    }

    // ── ② 컬럼 이름 ────────────────────────────────────────────────────────
    test("테이블마다 같은 컬럼 집합을 말한다") {
        val actual = query(
            """
            SELECT table_name, column_name FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
            """.trimIndent(),
        ).groupBy({ it[0]!! }, { it[1]!! }).mapValues { it.value.toSet() }

        erd.keys.intersect(actual.keys).flatMap { t ->
            val e = erd.getValue(t).map { it.name }.toSet()
            val a = actual.getValue(t)
            (e - a).map { "$t.$it — ERD 에만 있다" } + (a - e).map { "$t.$it — 스키마에만 있다" }
        }.shouldBeEmpty()
    }

    // ── ③ 컬럼 타입 ────────────────────────────────────────────────────────
    test("테이블마다 같은 컬럼 타입을 말한다") {
        val actual = query(
            """
            SELECT table_name, column_name, data_type FROM information_schema.columns
             WHERE table_schema = 'public' AND table_name <> 'flyway_schema_history'
            """.trimIndent(),
        ).associate { (t, c, d) -> "$t.$c" to d!! }

        erd.flatMap { (table, cols) ->
            cols.mapNotNull { col ->
                val expected = typeMap[col.type]
                    ?: return@mapNotNull "$table.${col.name} — ERD 타입 '${col.type}' 을 매핑할 수 없다"
                val got = actual["$table.${col.name}"] ?: return@mapNotNull null
                if (got != expected) "$table.${col.name} — ERD '$expected' vs 스키마 '$got'" else null
            }
        }.shouldBeEmpty()
    }

    // ── ④ 기본 키 ──────────────────────────────────────────────────────────
    test("테이블마다 같은 기본 키를 말한다") {
        val actual = query(
            """
            SELECT tc.table_name, kcu.column_name
              FROM information_schema.table_constraints tc
              JOIN information_schema.key_column_usage kcu
                ON kcu.constraint_name = tc.constraint_name
             WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_schema = 'public'
               AND tc.table_name <> 'flyway_schema_history'
            """.trimIndent(),
        ).groupBy({ it[0]!! }, { it[1]!! }).mapValues { it.value.toSet() }

        erd.keys.intersect(actual.keys).flatMap { t ->
            val e = erd.getValue(t).filter { it.isPk }.map { it.name }.toSet()
            val a = actual.getValue(t)
            if (e != a) listOf("$t — ERD PK $e vs 스키마 PK $a") else emptyList()
        }.shouldBeEmpty()
    }

    // ── ⑤ 외래 키 정책 ─────────────────────────────────────────────────────
    // ERD 파싱으로 판정하지 않는다. 점선(FK 아님)은 ERD 마커로 표현되지 않는다.
    // 이 목록이 docs/01-domain-model.md 의 「선이 있는 것과 FK 로 강제되는 것」 표다.
    test("외래 키가 문서화된 정책과 정확히 일치한다") {
        // pg_constraint 를 쓴다. information_schema 의 key_column_usage 와
        // constraint_column_usage 를 조인하면 복합 FK 에서 카테시안 곱이 생겨
        // 컬럼이 중복 나열된다 (3열 FK 가 9개로 불어난다).
        val actual = query(FK_QUERY)
            .map { (from, cols, to) -> "$from($cols) -> $to" }.sorted()

        actual shouldContainExactly listOf(
            // 선점은 예약과 같은 룸타입·수량이어야 한다. reservation_id 단독으로는
            // 예약의 존재만 보장되고, 룸타입 A 예약에 룸타입 B 재고를 선점할 수 있다.
            "channel_policy(room_type_id) -> room_type",
            "daily_inventory(room_type_id) -> room_type",
            "inventory_hold(reservation_id,room_type_id,room_count) -> reservation",
            "inventory_hold(room_type_id,stay_date) -> daily_inventory",
            "reservation(room_type_id) -> room_type",
            "room_type(property_id) -> property",
        ).sorted()
    }

    test("의도적으로 없는 FK 가 생기지 않았다") {
        val fkTables = query(FK_QUERY).map { "${it[0]} -> ${it[2]}" }.toSet()

        // channel_policy -> daily_inventory 를 걸면 재고 행이 아직 없는 미래 날짜에
        // 노출 상한을 미리 설정하는 것이 막힌다. 캡형 운영(#36)에서 필요한 동작이다.
        //
        // outbox_event 와 inbound_message 는 다형 참조이거나 수신 시점에 대상이
        // 없으므로 FK 를 두지 않는다 (ADR-0003 · ADR-0009).
        listOf(
            "channel_policy -> daily_inventory",
            "outbox_event -> reservation",
            "inbound_message -> reservation",
            "inbound_message -> channel_policy",
        ).filter { it in fkTables }
            .map { "$it — 의도적으로 두지 않은 FK 다. 근거는 docs/01-domain-model.md" }
            .shouldBeEmpty()
    }
}) {
    override fun extensions() = listOf(SpringExtension)

    companion object {
        /** (참조하는 테이블, 컬럼 목록, 참조되는 테이블). 복합 FK 도 한 행으로 나온다. */
        private const val FK_QUERY = """
            SELECT c.conrelid::regclass::text AS from_table,
                   (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                      FROM unnest(c.conkey) WITH ORDINALITY AS k(attnum, ord)
                      JOIN pg_attribute a
                        ON a.attrelid = c.conrelid AND a.attnum = k.attnum) AS cols,
                   c.confrelid::regclass::text AS to_table
              FROM pg_constraint c
             WHERE c.contype = 'f' AND c.connamespace = 'public'::regnamespace
        """
    }
}
