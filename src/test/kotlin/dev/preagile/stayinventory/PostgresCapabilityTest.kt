package dev.preagile.stayinventory

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.testcontainers.containers.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/**
 * 스택이 주장하는 전제를 실행해서 확인한다.
 *
 * 문서는 `inbound_message` 의 멱등이 `UNIQUE NULLS NOT DISTINCT` 에 걸려 있고
 * 그것이 PostgreSQL 15 이상 기능이라고 적었다. 문서에만 있는 전제는 검증되지 않는다.
 * 컨테이너 이미지가 언젠가 내려가거나 로컬 DB 가 구버전이면 조용히 깨지는데,
 * 그때 드러나는 증상은 "중복이 들어온다" 이고 원인까지 거슬러 올라가기 어렵다.
 */
class PostgresCapabilityTest : FunSpec({

    lateinit var postgres: PostgreSQLContainer<*>

    beforeSpec {
        postgres = PostgreSQLContainer("postgres:16-alpine")
        postgres.start()
    }

    afterSpec {
        postgres.stop()
    }

    fun connect(): Connection =
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)

    test("서버 메이저 버전이 15 이상이다 — NULLS NOT DISTINCT 의 하한") {
        connect().use { conn ->
            val major = conn.metaData.databaseMajorVersion
            major shouldBeGreaterThanOrEqual 15
        }
    }

    test("기본 UNIQUE 는 NULL 이 섞인 중복을 막지 못한다 — 이것이 막으려는 결함이다") {
        connect().use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE loose_unique (
                        channel      VARCHAR(32)  NOT NULL,
                        external_id  VARCHAR(128) NOT NULL,
                        sequence_key VARCHAR(128),
                        UNIQUE (channel, external_id, sequence_key)
                    )
                    """.trimIndent(),
                )
                val insert = "INSERT INTO loose_unique VALUES ('YANOLJA', 'BK-1', NULL)"
                st.executeUpdate(insert)
                // 같은 값을 다시 넣는데 통과한다. NULL = NULL 이 참이 아니기 때문이다.
                st.executeUpdate(insert)

                st.executeQuery("SELECT count(*) FROM loose_unique").use { rs ->
                    rs.next()
                    rs.getInt(1) shouldBe 2
                }
            }
        }
    }

    test("NULLS NOT DISTINCT 는 같은 중복을 막는다") {
        connect().use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE strict_unique (
                        channel      VARCHAR(32)  NOT NULL,
                        external_id  VARCHAR(128) NOT NULL,
                        sequence_key VARCHAR(128),
                        UNIQUE NULLS NOT DISTINCT (channel, external_id, sequence_key)
                    )
                    """.trimIndent(),
                )
                val insert = "INSERT INTO strict_unique VALUES ('YANOLJA', 'BK-1', NULL)"
                st.executeUpdate(insert)

                val failed =
                    try {
                        st.executeUpdate(insert)
                        false
                    } catch (e: SQLException) {
                        // 23505 = unique_violation. 메시지 문구가 아니라 SQLSTATE 로 판정한다.
                        // 드라이버 예외 타입(PSQLException)에 기대지 않는다 — 표준 SQLState 로 충분하고,
                        // 그러면 드라이버를 테스트 컴파일 경로에 올릴 필요가 없다.
                        e.sqlState shouldBe "23505"
                        true
                    }
                failed shouldBe true

                st.executeQuery("SELECT count(*) FROM strict_unique").use { rs ->
                    rs.next()
                    rs.getInt(1) shouldBe 1
                }
            }
        }
    }
})
