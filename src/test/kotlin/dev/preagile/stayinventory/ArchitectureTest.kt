package dev.preagile.stayinventory

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import jakarta.persistence.ElementCollection
import jakarta.persistence.Entity
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinTable
import jakarta.persistence.ManyToMany
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.OneToOne

/**
 * 정합성 보장 구조가 **우회되지 않는지** 검사한다.
 *
 * 여기 있는 규칙들이 막는 것은 코드 스타일이 아니라 `dirty checking` 이다.
 * ArchUnit 은 정적 참조를 보는데, 연관을 타고 들어가 필드를 바꾸면
 * `InventoryService` 에도 Repository 에도 정적 참조가 생기지 않으면서 UPDATE 가
 * 나간다. 잡을 수 없는 경로는 만들지 않는 것이 유일한 방어다 (ADR-0008).
 *
 * ## 아직 대상이 없는 규칙
 *
 * `docs/03-testing-strategy.md` 는 규칙 6개를 적어 두었고 그중 셋은 대상 코드가
 * 아직 없다. 빈 패키지에 건 규칙은 **항상 통과하므로 있으나 마나다** --
 * 이 저장소에서 도달하지 못하는 가드를 이미 세 번 만들었다.
 *
 * 그래서 규칙을 미리 걸어 두는 대신 **대상이 생기는 순간 이 스펙이 실패하게** 했다.
 * 아래 「아직 오지 않은 규칙」 절이 그 장치다. 잊을 수 있는 형태로 두지 않는다.
 */
class ArchitectureTest : FunSpec({

    val production: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("dev.preagile.stayinventory")

    /** 연관 매핑을 만드는 어노테이션 전부. 하나라도 빠지면 그 구멍으로 경로가 생긴다. */
    val associationAnnotations = listOf(
        OneToMany::class.java,
        ManyToOne::class.java,
        OneToOne::class.java,
        ManyToMany::class.java,
        JoinColumn::class.java,
        JoinTable::class.java,
        ElementCollection::class.java,
    )

    test("검사 대상이 비어 있지 않다 — 빈 집합에 건 규칙은 항상 통과한다") {
        // Given: 프로덕션 클래스 전부
        val entities = production.filter { it.isAnnotatedWith(Entity::class.java) }

        // Then: 엔티티 8개가 잡혀야 아래 규칙들이 의미를 갖는다
        entities.map { it.simpleName } shouldContainExactlyInAnyOrder listOf(
            "Property", "RoomType", "DailyInventory", "Reservation",
            "InventoryHold", "OutboxEvent", "InboundMessage", "ChannelPolicy",
        )
        production.size shouldBeGreaterThanOrEqual 8
    }

    test("엔티티에 연관 매핑이 없다 — dirty checking 우회 경로를 만들지 않는다") {
        // Given: 엔티티에 선언된 필드와 메서드
        associationAnnotations.forEach { annotation ->
            // When / Then: 필드 쪽
            noFields()
                .that().areDeclaredInClassesThat().areAnnotatedWith(Entity::class.java)
                .should().beAnnotatedWith(annotation)
                .because(
                    "연관을 타고 들어간 필드 변경은 InventoryService 도 Repository 도 " +
                        "거치지 않고 UPDATE 를 만든다 (ADR-0008 · 절대 규칙 12)",
                )
                .check(production)

            // 프로퍼티 접근 방식이 바뀌면 어노테이션이 게터로 옮겨 간다.
            // 필드만 보면 그 이동이 규칙을 통째로 빠져나간다.
            noMethods()
                .that().areDeclaredInClassesThat().areAnnotatedWith(Entity::class.java)
                .should().beAnnotatedWith(annotation)
                .allowEmptyShould(true)
                .check(production)
        }
    }

    test("도메인 패키지는 JPA·웹 어노테이션을 모르는 채로 있다") {
        // Given: 상태 기계와 값 집합만 있는 패키지
        // Then: 여기에 @Entity 가 들어오면 상태 규칙이 영속 관심사와 뒤섞이고,
        // 순수 단위 테스트로 전이표를 검사하지 못하게 된다
        noClasses()
            .that().resideInAPackage("..stayinventory.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jakarta.persistence..",
                "org.hibernate..",
                "org.springframework.web..",
                "org.springframework.data..",
            )
            .because("도메인 규칙은 저장 방식과 독립이어야 단위 테스트로 고정된다")
            .check(production)
    }

    test("영속 패키지는 웹 계층을 모른다 — 엔티티가 응답 DTO 로 새어 나가지 않는다") {
        noClasses()
            .that().resideInAPackage("..stayinventory.persistence..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..")
            .because("엔티티를 그대로 응답에 실으면 스키마 변경이 곧 API 변경이 된다")
            .check(production)
    }

    // ── 아직 오지 않은 규칙 ────────────────────────────────────────────────
    test("대상이 생기면 규칙도 함께 와야 한다 — 미착수 패키지 목록") {
        // Given: docs/03-testing-strategy.md 가 규칙을 약속했지만 대상이 없는 패키지
        val notYet = mapOf(
            "..stayinventory.outbox.relay.." to "릴레이는 JPA Repository 에 의존하지 않는다 (#4 · #8)",
            "..stayinventory.inventory.." to "재고 변경은 InventoryService 를 통해서만 (#3)",
            "..stayinventory.channel.." to "ChannelAdapter 는 Repository 를 직접 참조하지 않는다 (#4)",
        )

        // When: 각 패키지에 클래스가 생겼는지 본다
        val appeared = notYet.keys.filter { pattern ->
            val bare = pattern.removePrefix("..").removeSuffix("..")
            production.any { it.packageName.contains(bare) }
        }

        // Then: 생겼다면 이 테스트가 실패한다. 실패를 보고 규칙을 여기 옮겨 적고
        // 이 목록에서 지우는 것이 다음 사람이 할 일이다.
        //
        // 규칙을 지금 미리 걸어 두는 쪽을 택하지 않았다. 빈 패키지에 건 규칙은
        // 항상 통과해서, 대상이 생긴 뒤에도 통과하는지 아무도 확인하지 않는다.
        appeared.map { notYet.getValue(it) } shouldBe emptyList()
    }
})
