package dev.preagile.stayinventory.domain

/**
 * 예약 상태와 **전이 규칙**.
 *
 * 전이표를 코드에 데이터로 둔다. `if` 로 흩어 놓으면 "어떤 상태에서 어디로 갈 수
 * 있는가" 를 테스트할 대상이 없어지고, 규칙이 호출부마다 조금씩 달라진다.
 *
 * 근거: `docs/01-domain-model.md` 「예약 상태 전이」
 */
enum class ReservationStatus {
    /** 선점 획득. 결제 전이므로 `sold` 에 들어가지 않는다. */
    HELD,

    /** 승인형 채널의 결제 완료. 사장님 판단을 기다린다. 아직 `sold` 가 아니다. */
    PENDING_APPROVAL,

    /** 확정. **여기서 처음으로 `sold` 에 들어간다.** */
    CONFIRMED,
    CHECKED_IN,
    CHECKED_OUT,

    /** 노쇼. 그 날짜 방을 잡아 두었고 되팔 수도 없었으므로 점유로 센다. */
    TERMINATED,

    EXPIRED,
    REJECTED,
    CANCELED,
    ;

    val isTerminal: Boolean get() = allowedNext.isEmpty()

    val allowedNext: Set<ReservationStatus> get() = TRANSITIONS.getValue(this)

    fun canTransitionTo(next: ReservationStatus): Boolean = next in allowedNext

    /**
     * 불법 전이를 예외로 막는다.
     *
     * 같은 상태로의 전이(`CONFIRMED -> CONFIRMED`)도 불법이다. 멱등이 필요한 자리는
     * 조건부 UPDATE 의 `rowcount` 로 판정하지 이 함수로 판정하지 않는다
     * (`docs/01-domain-model.md` 재고 복원 알고리즘 1~2번).
     */
    fun requireTransitionTo(next: ReservationStatus) {
        require(canTransitionTo(next)) {
            "$this -> $next 는 허용되지 않는 전이다. 허용: ${allowedNext.joinToString()}"
        }
    }

    companion object {
        /**
         * `sold` 에 반영된 채로 남아 있는 상태들. `INV-2` 의 카운트 대상이며
         * **이 집합이 그 정의의 단일 진실 원천이다.**
         *
         * `CHECKED_OUT` 과 `TERMINATED` 가 여기 있는 것이 직관에 반한다. 기준은
         * 타임라인 위의 구간이 아니라 "`sold` 에 들어간 뒤 되돌려졌는가" 다.
         * 되돌린 것은 `CANCELED` 뿐이다.
         */
        val OCCUPYING: Set<ReservationStatus> =
            setOf(CONFIRMED, CHECKED_IN, CHECKED_OUT, TERMINATED)

        /**
         * 재고를 선점만 하고 있는 상태들. `INV-4` 의 후보이며 **후보일 뿐이다** --
         * 유효 선점 판정에는 `expires_at > now() AND released_at IS NULL` 이 함께 필요하다.
         */
        val HOLDING: Set<ReservationStatus> = setOf(HELD, PENDING_APPROVAL)

        private val TRANSITIONS: Map<ReservationStatus, Set<ReservationStatus>> = mapOf(
            HELD to setOf(EXPIRED, CONFIRMED, PENDING_APPROVAL),
            PENDING_APPROVAL to setOf(CONFIRMED, REJECTED, EXPIRED),
            // CONFIRMED 에서 갈 수 있는 종료는 CANCELED 하나다.
            CONFIRMED to setOf(CHECKED_IN, CANCELED),
            // CHECKED_IN -> CANCELED 를 만들지 않는다. 체크인 이후의 이탈은
            // TERMINATED 로 보낸다 -- 복원 조건 WHERE status = 'CONFIRMED' 가
            // 제외 목록 없이 성립하는 이유다 (docs/01-domain-model.md).
            CHECKED_IN to setOf(CHECKED_OUT, TERMINATED),
            CHECKED_OUT to emptySet(),
            TERMINATED to emptySet(),
            EXPIRED to emptySet(),
            REJECTED to emptySet(),
            CANCELED to emptySet(),
        )
    }
}
