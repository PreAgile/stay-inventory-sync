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
