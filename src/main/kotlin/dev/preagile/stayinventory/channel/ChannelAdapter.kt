package dev.preagile.stayinventory.channel

/**
 * 채널로 나가는 통보의 경계.
 *
 * **구현체는 리포지토리를 직접 참조하지 않는다** (`03-testing-strategy.md`
 * 아키텍처 규칙). 어댑터가 DB 를 읽기 시작하면 "발행 시점에 재고를 다시 조회하는"
 * 경로가 생기고, 그것은 at-least-once 가 아니라 순서 없는 최신값 전송이 된다.
 * 어댑터는 **받은 payload 만** 보낸다.
 */
interface ChannelAdapter {
    val channel: String

    /**
     * @param idempotencyKey `outbox_event.id`. 같은 키의 재요청은 채널 쪽에서
     *   같은 사건으로 흡수돼야 한다 -- Outbox 는 at-least-once 이고
     *   **정확히 한 번은 발행 측이 아니라 수신 측에서 만들어진다** (ADR-0003).
     * @param payload 사건 발생 시점의 값. 여기서 다시 계산하지 않는다.
     */
    fun push(idempotencyKey: Long, payload: String): ChannelSyncResult

    /**
     * 노출 규칙을 보낸다. 재고와 **별개 경로**다.
     *
     * 채널 API 에서도 둘은 다른 자원이다 -- 재고는 `availability`, 규칙은
     * `max_availability` 같은 제한 필드다. 한 메서드로 합치면 payload 안의
     * 필드를 보고 분기해야 하고, **그 분기는 어댑터가 payload 의 구조를 안다**는 뜻이다.
     */
    fun pushPolicy(idempotencyKey: Long, payload: String): ChannelSyncResult

    /**
     * 지금 이 순간의 재고를 **절대값으로** 밀어넣는다. 정기 재동기화(`#23`)가 쓴다.
     *
     * `push` 와 성질이 다르다.
     *
     * | | `push` | `pushSnapshot` |
     * |---|---|---|
     * | 무엇을 보내나 | **사건 발생 시점**의 값 | **지금**의 값 |
     * | 멱등키 | `outbox_event.id` | 없다 |
     * | 재전송하면 | 흡수된다 | **다시 적용된다** |
     *
     * 마지막 줄이 이 메서드의 존재 이유다. 재동기화는 흡수되면 안 된다 --
     * 낡은 값이 채널에 남아 있는 상태를 고치는 것이 목적이므로, 같은 값을
     * 여러 번 보내는 것이 정상이고 매번 적용돼야 한다.
     *
     * 그래서 **멱등키를 받지 않는다.** 받으면 두 번째 재동기화가 흡수되어
     * 아무 일도 하지 않고, 그 사실이 조용하다.
     */
    fun pushSnapshot(roomTypeId: Long, stayDate: java.time.LocalDate, remaining: Int):
        ChannelSyncResult

    /**
     * 채널이 **지금 들고 있는** 재고를 읽어 온다. 대사(#6)가 쓴다.
     *
     * `push` 와 방향이 반대이고 성질도 반대다 -- `push` 는 "우리가 아는 값을
     * 밀어넣는" 것이고 이쪽은 **"상대가 무엇을 안다고 하는가" 를 묻는** 것이다.
     * 이 조회 결과를 상태에 반영하지 않는다. 대조에만 쓴다.
     *
     * 반환에 없는 (룸타입, 날짜)는 **채널이 그 날짜를 모른다**는 뜻이다.
     * 0 과 구분해야 한다 -- 0 은 "매진이라고 안다" 이고 부재는 "통보가 도달한
     * 적이 없다" 이며, 후자가 이 리포트가 잡으려는 것이다.
     */
    /**
     * 채널이 준 순서키를 **비교 가능한 값으로 정규화**한다 (ADR-0013).
     *
     * 형식이 채널마다 다르다 — 숫자 리비전 · ISO 타임스탬프 · 불투명 문자열.
     * **형식을 아는 것은 어댑터**이므로 여기서 변환한다. 도메인이 형식을 알면
     * 채널이 하나 늘 때 도메인이 바뀐다.
     *
     * 원본은 보존된다. 이 값은 정렬과 묘비 판정에만 쓰이고,
     * 멱등 판정은 **원본** `sequence_key` 에 걸린 채로 둔다.
     *
     * @return 비교 가능한 값. **정규화할 수 없으면 `null`** —
     *   순서를 복원할 수 없다는 뜻이고, 그 채널은 drift 검출에 더 의존해야 한다.
     */
    fun sequenceRank(sequenceKey: String?): Long?

    fun currentInventory(roomTypeId: Long, from: java.time.LocalDate, to: java.time.LocalDate):
        Map<java.time.LocalDate, Int>
}

/**
 * 채널 응답의 네 갈래.
 *
 * **`RateLimited` 를 `Permanent` 로 오분류하지 않는 것이 이 타입의 존재 이유다.**
 * 레이트 리밋은 실패가 아니라 *"나중에 다시 하라"* 는 신호인데, 영구 실패로 읽으면
 * 정상 채널의 이벤트가 통째로 DLQ 로 떨어진다. 그리고 그것은 조용하다 --
 * 채널은 아무 문제도 보고하지 않았기 때문이다.
 *
 * `sealed interface` 라서 갈래가 늘면 `when` 이 컴파일에서 막는다. `else` 를
 * 붙이면 그 검사가 사라지므로 붙이지 않는다 (ADR-0005).
 */
sealed interface ChannelSyncResult {
    /** 반영됐다. [deduplicated] 면 채널이 멱등키로 흡수했다는 뜻이다. */
    data class Success(val deduplicated: Boolean = false) : ChannelSyncResult

    /**
     * 429. [retryAfterSeconds] 는 채널이 알려 준 대기 시간이고, 없으면 백오프를 쓴다.
     * Channex 공개 문서는 429 시 **최소 1분 중지**를 요구한다.
     */
    data class RateLimited(val retryAfterSeconds: Long?) : ChannelSyncResult

    /** 5xx · 타임아웃 · 연결 실패. 같은 요청이 나중에 성공할 수 있다. */
    data class Retryable(val reason: String) : ChannelSyncResult

    /** 4xx(429 제외). 같은 요청은 몇 번을 보내도 실패한다. 사람이 봐야 한다. */
    data class Permanent(val reason: String) : ChannelSyncResult
}
