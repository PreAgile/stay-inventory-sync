package dev.preagile.stayinventory.support

import dev.preagile.stayinventory.PostgresTestContainer
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * **훅이 실제로 도는지** 본다. JUnit Jupiter 쪽 절반이다.
 *
 * 이 저장소의 기본 스타일은 Kotest `FunSpec` 이다. 이 클래스만 Jupiter 인 이유는
 * **Jupiter 로 쓴 테스트가 검사를 건너뛰지 않는지**가 검증 대상이기 때문이다.
 * 규약이 지목한 실패 형태가 바로 이것이다 --
 *
 * > 불변식 훅을 Kotest 확장으로만 만들면 JUnit 스타일 테스트는 검사를 조용히
 * > 건너뛴다. 통과했다는 신호는 그대로 나오고 검사만 사라진다.
 *
 * 그래서 이 클래스는 **애노테이션으로 확장을 붙이지 않는다.** `@ExtendWith` 를
 * 붙여서 통과하면 "붙이면 걸린다" 만 증명한 것이고, 붙이는 것을 잊은 다음 테스트는
 * 여전히 검사를 건너뛴다. 자동 등록(`META-INF/services` +
 * `junit.jupiter.extensions.autodetection.enabled`)이 도는지를 봐야 한다.
 *
 * 생성자 주입을 쓰지 않는다. Spring 의 `SpringExtension` 은 기본 설정
 * (`spring.test.constructor.autowire.mode=ANNOTATED`)에서 생성자 파라미터를
 * 해석하지 않아 `ParameterResolutionException` 으로 죽는다 -- Kotest 쪽 확장이
 * 그것을 대신 해 주고 있어서 이 스펙에서 처음 드러났다. 여기서 필요한 것은
 * `DataSource` 참조가 아니라 **컨텍스트가 뜨는 것**(= 훅 무장)뿐이다.
 *
 * 두 메서드의 실행 순서를 고정한다. 엔진이 달라 Kotest 쪽 실행 순서에 기댈 수 없고,
 * 기대면 이 증명이 실행 순서 운에 의존하게 된다.
 */
@SpringBootTest
@Import(PostgresTestContainer::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class InvariantHookJUnitReachabilityTest {

    @Test
    @Order(1)
    fun `먼저 Jupiter 테스트를 하나 지나간다`() {
        // Given / When: 이 메서드가 끝나면 자동 등록된 AfterTestExecutionCallback 이 돈다
        assertTrue(true)
    }

    @Test
    @Order(2)
    fun `JUnit 엔진에서 훅이 돌았다 — 애노테이션 없이 걸렸다`() {
        // Then: 0 이면 자동 등록이 깨진 것이다.
        // junit-platform.properties 의 한 줄이나 META-INF/services 파일 중
        // 하나만 사라져도 이 숫자가 0 이 되고, 다른 어떤 신호도 나오지 않는다
        assertTrue(
            InvariantHook.checkCount(InvariantHook.Engine.JUNIT) >= 1,
            "JUnit 쪽 훅이 한 번도 돌지 않았다 — 자동 등록이 끊겼다",
        )
    }
}
