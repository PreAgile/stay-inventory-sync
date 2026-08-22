package dev.preagile.stayinventory

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import dev.preagile.stayinventory.persistence.DailyInventory
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

        // 아래 두 규칙의 대상 패키지가 실제로 비어 있지 않아야 규칙이 의미를 갖는다
        production.any { it.packageName.endsWith("outbox.relay") } shouldBe true
        production.any { it.packageName.endsWith("channel") } shouldBe true
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

    test("재고 카운터는 InventoryService 밖에서 바뀌지 않는다") {
        // Given: sold 를 옮기는 세터
        // Then: 이 규칙이 성립하려면 연관 매핑이 없어야 한다. 연관을 타고 들어간
        // 필드 변경은 InventoryService 에도 Repository 에도 정적 참조를 남기지
        // 않으면서 UPDATE 를 만들고, ArchUnit 은 정적 참조를 본다 (ADR-0008)
        noClasses()
            .that().resideOutsideOfPackages("..stayinventory.inventory..")
            .should().callMethod(DailyInventory::class.java, "setSold", Int::class.java)
            .because("재고 변경 경로가 둘이 되면 어느 쪽이 락을 잡았는지 판정할 수 없다")
            .check(production)
    }

    test("영속 패키지는 재고 서비스를 모른다 — 의존 방향이 한쪽이다") {
        // 엔티티가 서비스를 부르기 시작하면 "누가 누구를 통제하는가" 가 뒤집히고,
        // 위 규칙이 검사할 경계 자체가 사라진다
        noClasses()
            .that().resideInAPackage("..stayinventory.persistence..")
            .should().dependOnClassesThat().resideInAPackage("..stayinventory.inventory..")
            .check(production)
    }

    test("릴레이는 JPA 를 모른다 — 영속성 컨텍스트가 닿지 않는다") {
        // Given: outbox.relay 패키지
        // Then: 영속성 컨텍스트가 여기까지 오면 dirty checking 도 따라온다.
        // 릴레이가 실수로 도메인 엔티티를 바꿔 UPDATE 를 만드는 경로가 생기고,
        // 그 UPDATE 는 InventoryService 에도 Repository 에도 정적 참조를 남기지 않는다.
        //
        // 그리고 B3(FOR UPDATE SKIP LOCKED)는 JPA 표준에 없다. 어차피 네이티브로
        // 내려가야 하는 코드를 리포지토리 관례로 위장하지 않는다 (ADR-0008 결정 3)
        noClasses()
            .that().resideInAPackage("..stayinventory.outbox.relay..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..stayinventory.persistence..",
                "jakarta.persistence..",
                "org.springframework.data..",
            )
            .because("릴레이 경로에 JPA 가 닿으면 dirty checking 우회 경로가 다시 생긴다")
            .check(production)
    }

    test("채널 어댑터는 리포지토리를 직접 참조하지 않는다") {
        // Given: ChannelAdapter 구현체
        // Then: 어댑터가 DB 를 읽기 시작하면 "발행 시점에 재고를 다시 조회하는"
        // 경로가 생긴다. 그러면 재발행 사이에 낀 취소가 같은 이벤트를 다른 내용으로
        // 내보내고, at-least-once 가 아니라 순서 없는 최신값 전송이 된다 (#4)
        noClasses()
            .that().resideInAPackage("..stayinventory.channel..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "..stayinventory.persistence..",
                "..stayinventory.inventory..",
                "org.springframework.data..",
            )
            .because("어댑터는 받은 payload 만 보낸다. 다시 계산하지 않는다")
            .check(production)
    }

    test("정책 축은 재고 축을 만지지 않는다 — 두 축이 섞이면 원본이 둘이 된다") {
        // Given: channel_policy 를 다루는 패키지
        // Then: ADR-0009 가 세 축을 나누고 축마다 원본을 하나로 정했다.
        // 정책 코드가 재고 서비스를 부르기 시작하면 "캡을 걸었더니 재고가 줄었다"
        // 같은 경로가 생기고, 그 순간 캡형이 아니라 배정이 된다 (ADR-0001 기각안)
        noClasses()
            .that().resideInAPackage("..stayinventory.policy..")
            .should().dependOnClassesThat().resideInAPackage("..stayinventory.inventory..")
            .because("캡은 노출 상한이지 판매 상한이 아니다. 재고 모델을 바꾸면 배정이다")
            .check(production)
    }

    // ── 아직 오지 않은 규칙 ────────────────────────────────────────────────
    test("대상이 생기면 규칙도 함께 와야 한다 — 미착수 패키지 목록") {
        // Given: docs/03-testing-strategy.md 가 규칙을 약속했지만 대상이 없는 패키지
        // 비어 있다. 약속한 규칙 여섯이 전부 대상과 함께 들어왔다.
        //
        // 목록을 지우지 않고 남긴다 -- 다음에 "대상이 없어서 규칙을 미룬다" 는
        // 판단을 할 때 여기에 적으면 되고, 적으면 잊을 수 없다.
        val notYet = emptyMap<String, String>()

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
