package dev.preagile.stayinventory.support

import com.tngtech.archunit.core.importer.ClassFileImporter
import dev.preagile.stayinventory.PostgresTestContainer
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import javax.sql.DataSource

/**
 * **훅이 실제로 도는지** 본다. Kotest 쪽 절반이다.
 *
 * 훅을 만들었다는 것과 훅이 걸렸다는 것은 다르다. `io.kotest.provided.ProjectConfig`
 * 는 패키지와 이름이 정확할 때만 발견되고, 틀리면 **아무 오류 없이** 등록되지
 * 않는다. 그 실패는 초록불로 나타나므로 숫자로 확인한다.
 *
 * JUnit 쪽 절반은 [InvariantHookJUnitReachabilityTest] 에 있다. 한 파일에 두지
 * 않은 이유는 엔진이 다르면 스펙 스타일도 달라야 하기 때문이다 -- 그리고 그
 * "다른 엔진" 이 이 이슈의 전부다.
 */
@SpringBootTest
@Import(PostgresTestContainer::class)
class InvariantHookReachabilityTest(
    @Suppress("unused") private val dataSource: DataSource,
) : FunSpec({

    test("먼저 DB 컨텍스트를 한 번 지나간다 — 훅이 무장되는 지점") {
        // Given: PostgresTestContainer 를 @Import 한 스펙
        // Then: 컨텍스트가 떴다는 것이 곧 InvariantHook.arm 이 불렸다는 뜻이다.
        // 이 테스트가 끝나는 순간 Kotest 훅이 한 번 돈다
        true shouldBe true
    }

    test("Kotest 엔진에서 훅이 돌았다") {
        // Given: 바로 위 테스트가 끝났다
        // Then: 0 이면 ProjectConfig 가 발견되지 않은 것이다.
        // 그 실패는 예외가 아니라 침묵으로 오므로 여기서만 드러난다
        InvariantHook.checkCount(InvariantHook.Engine.KOTEST) shouldBeGreaterThanOrEqual 1
    }

    test("INV-2 면제는 이 목록뿐이다 — 새 면제는 이 테스트를 함께 고쳐야 들어온다") {
        // Given: 테스트 클래스 전부
        val testClasses = ClassFileImporter().importPackages("dev.preagile.stayinventory")

        // When: DirectRowSpec 을 구현한 스펙을 모은다
        val exempted = testClasses
            .filter { it.isAssignableTo(DirectRowSpec::class.java) && !it.isInterface }
            .map { it.simpleName }

        // Then: 면제는 리뷰를 거쳐야 는다. 플래그였다면 이 목록을 만들 수조차 없다.
        //
        // SchemaMigrationTest 만 면제다 -- 스키마 제약을 시험하려고 도메인 연산 없이
        // 행을 직접 넣으므로 INV-2 가 성립할 이유가 없다. 다른 스펙이 여기 들어오려면
        // "도메인을 거치지 않고 행을 넣는다" 는 사실 자체가 정당해야 한다
        exempted shouldContainExactlyInAnyOrder listOf("SchemaMigrationTest")
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
