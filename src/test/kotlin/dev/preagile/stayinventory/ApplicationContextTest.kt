package dev.preagile.stayinventory

import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

/**
 * 애플리케이션이 실제 PostgreSQL 에 붙어 뜨는지 본다.
 *
 * H2 로 하지 않는 이유는 `SELECT FOR UPDATE` 의 의미가 다르기 때문이다 —
 * 뒤에 붙을 락 검증이 H2 에서는 통과해도 아무것도 증명하지 못한다.
 * 그 결정을 첫 테스트부터 지킨다.
 */
@SpringBootTest
@Import(PostgresTestContainer::class)
class ApplicationContextTest(
    private val dataSource: DataSource,
    private val postgres: PostgreSQLContainer<*>,
) : FunSpec({

    test("컨텍스트가 뜨고 컨테이너의 PostgreSQL 에 붙는다") {
        dataSource.connection.use { conn ->
            conn.metaData.databaseProductName shouldBe "PostgreSQL"
            conn.metaData.databaseMajorVersion shouldBeGreaterThanOrEqual 15

            // 로컬 5432 가 아니라 컨테이너의 임의 포트여야 한다.
            // 이 검사가 없으면 @ServiceConnection 이 깨져도 로컬 DB 로 붙어 통과한다.
            conn.metaData.url shouldContain ":${postgres.getMappedPort(5432)}/"
        }
    }

    test("Flyway 가 V1 을 적용하고 이력에 성공으로 남긴다") {
        dataSource.connection.use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT version, success FROM flyway_schema_history ORDER BY installed_rank",
                ).use { rs ->
                    rs.next()
                    rs.getString("version") shouldBe "1"
                    // success = false 인 이력이 남아도 컨텍스트는 뜬다.
                    // 버전만 확인하면 실패한 마이그레이션을 통과로 읽는다.
                    rs.getBoolean("success") shouldBe true
                }
            }
        }
    }
}) {
    // 생성자 주입과 컨텍스트 로딩은 이 확장이 담당한다.
    override fun extensions() = listOf(SpringExtension)
}
