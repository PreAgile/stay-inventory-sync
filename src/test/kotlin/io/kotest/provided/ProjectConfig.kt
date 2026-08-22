package io.kotest.provided

import dev.preagile.stayinventory.support.InvariantHook
import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.listeners.AfterTestListener
import io.kotest.core.test.TestCase
import io.kotest.core.test.TestResult

/**
 * Kotest 쪽 훅 등록.
 *
 * 패키지와 클래스 이름이 `io.kotest.provided.ProjectConfig` 여야 Kotest 가 찾는다.
 * 다른 곳에 두면 **아무 오류 없이 등록되지 않는다** -- 이 파일 위치가 곧 계약이다.
 */
class ProjectConfig : AbstractProjectConfig() {
    override fun extensions() = listOf(InvariantAfterTestListener)
}

/**
 * 테스트 하나가 끝날 때마다 불변식을 본다.
 *
 * 실패한 테스트 뒤에도 돈다. 실패로 트랜잭션이 롤백됐다면 불변식은 여전히
 * 참이어야 하고, 참이 아니라면 **롤백이 안 된 것**이 진짜 문제다.
 */
object InvariantAfterTestListener : AfterTestListener {
    override suspend fun afterAny(testCase: TestCase, result: TestResult) {
        InvariantHook.verify(testCase.spec, InvariantHook.Engine.KOTEST)
    }
}
