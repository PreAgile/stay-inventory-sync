package dev.preagile.stayinventory.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * 상태 전이 규칙이 **코드에 데이터로** 존재하는지 본다.
 *
 * 전이표를 `if` 로 흩어 놓으면 검사할 대상이 없고, 호출부마다 규칙이 조금씩
 * 달라진다. 여기서 고정하는 것은 전이표 그 자체이며, `docs/01-domain-model.md`
 * 의 상태 다이어그램이 이 스펙의 원본이다.
 *
 * 이 스펙이 지키는 두 가지가 나머지 전부를 떠받친다.
 *
 * - `OCCUPYING` 이 `INV-2` 의 카운트 대상을 정의한다. 여기가 틀리면 재고 카운터와
 *   예약 사실을 대조하는 등식 자체가 틀린 것을 검사하게 된다
 * - `CHECKED_IN -> CANCELED` 가 **없다**는 것이 복원 조건
 *   `WHERE status = 'CONFIRMED'` 가 제외 목록 없이 성립하는 근거다
 */
class ReservationStatusTest : FunSpec({

    // ── 전이표 ────────────────────────────────────────────────────────────
    test("모든 상태가 전이 규칙을 갖는다 — 새 상태가 규칙 없이 추가되지 못한다") {
        // Given: 선언된 상태 전부
        // When / Then: 하나라도 규칙이 없으면 여기서 예외로 드러난다.
        // 규칙 없는 상태는 "어디로도 못 간다" 가 아니라 "아무도 안 정했다" 이고,
        // 둘은 결과가 같아 보여서 조용하다.
        ReservationStatus.entries.forEach { it.allowedNext }
    }

    test("선점에서 갈 수 있는 곳은 만료·즉시확정·승인대기 셋뿐이다") {
        ReservationStatus.HELD.allowedNext shouldContainExactlyInAnyOrder setOf(
            ReservationStatus.EXPIRED,
            ReservationStatus.CONFIRMED,
            ReservationStatus.PENDING_APPROVAL,
        )
    }

    test("승인 대기에서 갈 수 있는 곳은 승인·거절·시한초과 셋뿐이다") {
        ReservationStatus.PENDING_APPROVAL.allowedNext shouldContainExactlyInAnyOrder setOf(
            ReservationStatus.CONFIRMED,
            ReservationStatus.REJECTED,
            ReservationStatus.EXPIRED,
        )
    }

    test("확정에서 갈 수 있는 종료는 취소 하나다") {
        // 확정 이후의 이탈 경로가 둘이면 복원 조건이 상태 하나로 판정되지 않는다.
        ReservationStatus.CONFIRMED.allowedNext shouldContainExactlyInAnyOrder setOf(
            ReservationStatus.CHECKED_IN,
            ReservationStatus.CANCELED,
        )
    }

    test("체크인 이후에는 취소가 없다 — 노쇼는 TERMINATED 로 간다") {
        // Given: 체크인한 예약
        val checkedIn = ReservationStatus.CHECKED_IN

        // Then: CANCELED 로 가는 길이 없어야 복원 조건이 성립한다.
        // 이 길이 열리면 WHERE status = 'CONFIRMED' 가 취소를 하나 놓치고,
        // 그 예약의 재고는 영원히 복원되지 않는다.
        checkedIn.canTransitionTo(ReservationStatus.CANCELED) shouldBe false
        checkedIn.allowedNext shouldContainExactlyInAnyOrder setOf(
            ReservationStatus.CHECKED_OUT,
            ReservationStatus.TERMINATED,
        )
    }

    test("종료 상태 다섯에서는 어디로도 가지 못한다") {
        val terminals = ReservationStatus.entries.filter { it.isTerminal }

        terminals shouldContainExactlyInAnyOrder listOf(
            ReservationStatus.CHECKED_OUT,
            ReservationStatus.TERMINATED,
            ReservationStatus.EXPIRED,
            ReservationStatus.REJECTED,
            ReservationStatus.CANCELED,
        )
    }

    test("불법 전이는 예외다 — 조용히 무시되지 않는다") {
        // Given: 이미 취소된 예약
        // When: 확정으로 되돌리려 한다
        val thrown = shouldThrow<IllegalArgumentException> {
            ReservationStatus.CANCELED.requireTransitionTo(ReservationStatus.CONFIRMED)
        }

        // Then: 무엇이 허용되는지가 메시지에 있어야 디버깅이 된다
        thrown.message!!.contains("CANCELED -> CONFIRMED") shouldBe true
    }

    test("같은 상태로의 전이도 불법이다 — 멱등은 rowcount 가 판정한다") {
        // 멱등을 이 함수로 판정하면 "이미 그 상태였다" 와 "지금 옮겼다" 가
        // 구분되지 않는다. 중복 취소 방어는 조건부 UPDATE 의 rowcount 가 한다.
        ReservationStatus.CONFIRMED.canTransitionTo(ReservationStatus.CONFIRMED) shouldBe false
    }

    // ── 불변식이 참조하는 집합 ────────────────────────────────────────────
    test("INV-2 가 세는 점유 집합은 넷이다 — CHECKED_OUT 과 TERMINATED 를 포함한다") {
        // 기준은 타임라인 위의 구간이 아니라 "sold 에 들어간 뒤 되돌려졌는가" 다.
        // 되돌린 것은 CANCELED 뿐이므로 체크아웃도 노쇼도 여전히 팔린 방이다.
        ReservationStatus.OCCUPYING shouldContainExactlyInAnyOrder setOf(
            ReservationStatus.CONFIRMED,
            ReservationStatus.CHECKED_IN,
            ReservationStatus.CHECKED_OUT,
            ReservationStatus.TERMINATED,
        )
    }

    test("선점 집합과 점유 집합은 겹치지 않는다 — 겹치면 INV-4 가 같은 방을 두 번 센다") {
        // Given: 두 집합
        val overlap = ReservationStatus.OCCUPYING intersect ReservationStatus.HOLDING

        // Then: INV-4 는 sold 와 유효 선점을 더한다. 한 예약이 양쪽에 들어가면
        // 자기 자신 때문에 재고가 없다고 판정된다.
        overlap shouldBe emptySet()
    }

    test("차감된 적 없는 종료 상태는 어느 집합에도 없다") {
        // EXPIRED · REJECTED 는 sold 에 들어간 적이 없고 선점도 이미 끝났다.
        // CANCELED 는 들어갔다가 되돌아왔다. 셋 다 세면 안 된다.
        listOf(
            ReservationStatus.EXPIRED,
            ReservationStatus.REJECTED,
            ReservationStatus.CANCELED,
        ).forEach { status ->
            (status in ReservationStatus.OCCUPYING) shouldBe false
            (status in ReservationStatus.HOLDING) shouldBe false
        }
    }

    test("상태 9개가 DB CHECK 제약과 같은 이름을 쓴다") {
        // enum 이름과 DB 허용 목록이 어긋나면 INSERT 가 23514 로 죽는다.
        // 그 실패는 런타임에만 드러나므로 여기서 이름을 고정한다.
        ReservationStatus.entries.map { it.name } shouldContainExactly listOf(
            "HELD", "PENDING_APPROVAL",
            "CONFIRMED", "CHECKED_IN", "CHECKED_OUT", "TERMINATED",
            "EXPIRED", "REJECTED", "CANCELED",
        )
    }
})
