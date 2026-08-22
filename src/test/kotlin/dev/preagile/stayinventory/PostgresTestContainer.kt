package dev.preagile.stayinventory

import dev.preagile.stayinventory.support.InvariantHook
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

/**
 * 컨테이너를 **빈으로** 선언한다.
 *
 * Testcontainers 의 `@Testcontainers` + `@Container` 는 JUnit 확장이라
 * Kotest 스펙에서 동작하지 않는다. 테스트 규약이 `FunSpec` 통일을 요구하므로
 * (`AGENTS.md`) 컨테이너 수명을 Spring 이 관리하게 한다.
 *
 * `@ServiceConnection` 이 접속 정보를 `spring.datasource.*` 로 넣는다.
 * 프로퍼티를 손수 등록하지 않는 이유는, 이름이 어긋나면 실패가 아니라
 * **로컬 DB 로 조용히 붙는 것**이기 때문이다.
 *
 * 이미지는 `postgres:16-alpine` 로 고정한다. `UNIQUE NULLS NOT DISTINCT` 가
 * PostgreSQL 15 이상 기능이므로 이 하한은 선택이 아니다.
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestContainer {

    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16-alpine")

    /**
     * 불변식 훅에 `DataSource` 를 넘긴다.
     *
     * 스펙마다 손으로 무장시키지 않는다. 이 설정을 `@Import` 하는 것이 곧 DB 를
     * 쓴다는 뜻이고, DB 를 쓰면 불변식 검사 대상이다. 무장을 별도 단계로 두면
     * 그 단계를 빠뜨린 스펙이 검사 없이 통과한다.
     */
    @Bean
    fun invariantHookArming(dataSource: DataSource): InitializingBean =
        InitializingBean { InvariantHook.arm(dataSource) }
}
