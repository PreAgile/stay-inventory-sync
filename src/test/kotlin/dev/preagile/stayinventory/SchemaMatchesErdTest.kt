package dev.preagile.stayinventory

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File

/**
 * ERD 와 마이그레이션이 같은 것을 말하는지 본다.
 *
 * `#2` 의 완료 게이트다. 이 프로젝트에서 **이미 한 번 일어난 사고**를 막는다 —
 * 설계 결정이 테이블 둘을 추가했는데 ERD 는 6개에 멈춰 있었다(PR #42).
 * 사람이 두 곳을 맞추는 규율에 기대면 다시 갈라진다.
 *
 * 문서를 파싱한다는 점이 낯설 수 있지만, 여기서는 ERD 가 **명세**다.
 * 어긋나면 SQL 이 아니라 ERD 를 고치는 것이 규약이고, 그 판단을 사람이 하도록
 * 이 테스트는 "어느 쪽이 맞다" 를 말하지 않고 **차이만** 드러낸다.
 */
class SchemaMatchesErdTest : FunSpec({

    val repoRoot = generateSequence(File(".").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    val erdColumns: Map<String, Set<String>> = run {
        val doc = File(repoRoot, "docs/01-domain-model.md").readText()
        val erd = Regex("```mermaid\\n(erDiagram.*?)```", RegexOption.DOT_MATCHES_ALL)
            .find(doc)!!.groupValues[1]
        Regex("^ {4}([A-Z_]+) \\{\\n(.*?)^ {4}\\}", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE))
            .findAll(erd)
            .associate { m ->
                m.groupValues[1].lowercase() to
                    m.groupValues[2].trim().lines()
                        .mapNotNull { it.trim().split(" ").filter(String::isNotBlank).getOrNull(1) }
                        .toSet()
            }
    }

    val sqlColumns: Map<String, Set<String>> = run {
        val sql = File(repoRoot, "src/main/resources/db/migration/V1__init.sql").readText()
        val ignored = setOf("PRIMARY", "CONSTRAINT", "FOREIGN", "UNIQUE")
        Regex("CREATE TABLE (\\w+) \\((.*?)\\n\\);", RegexOption.DOT_MATCHES_ALL)
            .findAll(sql)
            .associate { m ->
                m.groupValues[1] to
                    m.groupValues[2].lines()
                        .map(String::trim)
                        .filter { it.isNotEmpty() && !it.startsWith("--") }
                        .map { it.split(" ").first() }
                        .filterNot { it.uppercase() in ignored }
                        .filter { it.matches(Regex("[a-z_]+")) }
                        .toSet()
            }
    }

    test("ERD 와 V1 이 같은 테이블 집합을 말한다") {
        val onlyErd = erdColumns.keys - sqlColumns.keys
        val onlySql = sqlColumns.keys - erdColumns.keys
        // 어느 쪽이 맞다고 판정하지 않는다. 차이를 드러내고 사람이 고친다.
        (onlyErd.map { "ERD 에만: $it" } + onlySql.map { "V1 에만: $it" }).shouldBeEmpty()
    }

    test("ERD 와 V1 이 테이블마다 같은 컬럼 집합을 말한다") {
        val diffs = (erdColumns.keys intersect sqlColumns.keys).flatMap { table ->
            val erd = erdColumns.getValue(table)
            val sql = sqlColumns.getValue(table)
            (sql - erd).map { "$table.$it — V1 에만 있다 (ERD 에 추가한다)" } +
                (erd - sql).map { "$table.$it — ERD 에만 있다 (V1 에 추가하거나 ERD 에서 지운다)" }
        }
        diffs.shouldBeEmpty()
    }
})
