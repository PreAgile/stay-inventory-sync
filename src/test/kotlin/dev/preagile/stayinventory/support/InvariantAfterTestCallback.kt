package dev.preagile.stayinventory.support

import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * JUnit Jupiter 쪽 훅.
 *
 * **`META-INF/services` 로 자동 등록한다.** `@ExtendWith` 를 붙이는 방식이면
 * 붙이는 것을 잊은 테스트가 검사 없이 통과한다 -- 잊을 수 있는 형태로 두지 않는다.
 * `junit-platform.properties` 의 `junit.jupiter.extensions.autodetection.enabled`
 * 가 이 파일을 켠다. 둘 중 하나만 있으면 아무것도 걸리지 않는다.
 */
class InvariantAfterTestCallback : AfterTestExecutionCallback {

    override fun afterTestExecution(context: ExtensionContext) {
        InvariantHook.verify(context.testInstance.orElse(null), InvariantHook.Engine.JUNIT)
    }
}
