package dev.preagile.stayinventory.outbox.relay

import dev.preagile.stayinventory.channel.ChannelAdapter
import dev.preagile.stayinventory.channel.ChannelSyncResult
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * 커밋된 통보를 채널로 내보낸다.
 *
 * ## 이 클래스가 JPA 를 쓰지 않는 이유
 *
 * `ADR-0008` 결정 3 이다. `B3`(`FOR UPDATE SKIP LOCKED`)가 JPA 표준에 없어서
 * 결국 네이티브로 내려가야 하는데, **그 코드를 리포지토리 관례로 위장하지 않는다.**
 * 그리고 영속성 컨텍스트가 없으면 dirty checking 도 없으므로, 릴레이가 실수로
 * 도메인 엔티티를 바꿔 UPDATE 를 만드는 경로 자체가 사라진다. ArchUnit 이 이
 * 경계를 검사한다.
 *
 * ## payload 만 보낸다. 재고를 다시 조회하지 않는다
 *
 * **이것을 어기면 `T4` 가 무의미해진다.** 재발행 사이에 취소가 끼면 같은 이벤트가
 * 다른 내용으로 나가고, 그것은 at-least-once 가 아니라 **순서 없는 최신값 전송**이다.
 * 그 둘은 수신 측 멱등으로 흡수되는 성질이 다르다 -- 중복은 흡수되지만
 * 낡은 값 덮어쓰기는 흡수되지 않는다.
 *
 * ## 백오프는 임의 값이 아니다
 *
 * `1/2/4/8/15/30분` 은 Channex 공개 문서의 실제 재시도 스케줄이다
 * (`docs/04-capacity-and-limits.md`). 429 는 최소 1분 중지를 요구하므로
 * [ChannelSyncResult.RateLimited] 는 그 하한을 따로 적용한다.
 */
@Component
class OutboxRelay(
    private val jdbc: JdbcTemplate,
    private val adapters: List<ChannelAdapter>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        // 어댑터가 둘 이상이면 이 설계가 성립하지 않는다.
        //
        // outbox_event 한 행이 "발행 한 번" 을 뜻하는데, 어댑터 A 는 성공하고
        // B 는 실패하면 그 행을 PUBLISHED 로도 PENDING 으로도 둘 수 없다.
        // 재시도하면 A 가 같은 통보를 또 받는다 -- 멱등키로 흡수되지만
        // 레이트 리밋은 그대로 소모된다.
        //
        // 팬아웃 대상 규칙(#18)이 정해지지 않은 상태에서 어댑터를 늘리면
        // 그 공백이 조용히 코드에 박힌다. 부팅에서 막는다.
        require(adapters.size <= 1) {
            "채널 어댑터가 ${adapters.size}개다. 팬아웃 규칙(#18)이 정해지기 전에는 " +
                "outbox_event 한 행 = 발행 한 번 전제가 깨진다"
        }
    }

    fun drain(limit: Int = 100, now: Instant = Instant.now()): RelayReport {
        val adapter = adapters.firstOrNull() ?: return RelayReport()
        var report = RelayReport()

        claimPending(limit, now).forEach { event ->
            // 락 밖이다. 여기서 네트워크를 기다려도 재고 행은 아무도 붙잡고 있지 않다.
            val result = runCatching { adapter.push(event.id, event.payload) }
                .getOrElse { ChannelSyncResult.Retryable("어댑터 예외: ${it.message}") }

            report = when (result) {
                is ChannelSyncResult.Success -> {
                    markPublished(event.id, now)
                    report.copy(published = report.published + 1)
                }

                is ChannelSyncResult.RateLimited -> {
                    // 429 는 실패가 아니라 "나중에 다시 하라" 는 신호다.
                    // 채널이 알려 준 대기 시간이 있으면 그것을 따르되 1분 하한을 지킨다.
                    val wait = maxOf(result.retryAfterSeconds ?: 0L, RATE_LIMIT_MIN_WAIT_SECONDS)
                    scheduleRetry(event, now.plusSeconds(wait))
                    report.copy(rateLimited = report.rateLimited + 1)
                }

                is ChannelSyncResult.Retryable -> {
                    scheduleRetry(event, now.plus(backoffFor(event.retryCount)))
                    report.copy(retried = report.retried + 1)
                }

                is ChannelSyncResult.Permanent -> {
                    log.warn("영구 실패로 판정: id={} reason={}", event.id, result.reason)
                    markDead(event.id)
                    report.copy(dead = report.dead + 1)
                }
            }
        }
        return report
    }

    /**
     * 발행 대기 이벤트를 꺼낸다.
     *
     * `id` 순이다. 같은 키의 이벤트가 생성 순서대로 나가야 하고, 그 순서가
     * 백오프 때문에 깨지는 것이 `#9` 가 다루는 문제다.
     */
    fun claimPending(limit: Int, now: Instant = Instant.now()): List<PendingOutboxEvent> =
        jdbc.query(
            """
            SELECT id, aggregate_type, aggregate_id, event_type, payload::text, retry_count
              FROM outbox_event
             WHERE status = 'PENDING' AND next_attempt_at <= ?
             ORDER BY id
             LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                PendingOutboxEvent(
                    id = rs.getLong(1),
                    aggregateType = rs.getString(2),
                    aggregateId = rs.getLong(3),
                    eventType = rs.getString(4),
                    payload = rs.getString(5),
                    retryCount = rs.getInt(6),
                )
            },
            java.sql.Timestamp.from(now),
            limit,
        )

    /**
     * 발행 완료 표시.
     *
     * **채널 호출이 성공한 뒤에 일어난다.** 그 사이에 프로세스가 죽으면 같은
     * 이벤트가 다시 발행되고, 그것이 at-least-once 의 정의다 -- 버그가 아니라
     * 필연이다 (ADR-0003). 그래서 어댑터에 멱등키를 붙인다.
     */
    fun markPublished(id: Long, now: Instant = Instant.now()) {
        jdbc.update(
            "UPDATE outbox_event SET status = 'PUBLISHED', published_at = ? WHERE id = ?",
            java.sql.Timestamp.from(now),
            id,
        )
    }

    fun scheduleRetry(event: PendingOutboxEvent, nextAttemptAt: Instant) {
        jdbc.update(
            """
            UPDATE outbox_event
               SET retry_count = retry_count + 1, next_attempt_at = ?
             WHERE id = ?
            """.trimIndent(),
            java.sql.Timestamp.from(nextAttemptAt),
            event.id,
        )
    }

    fun markDead(id: Long) {
        jdbc.update("UPDATE outbox_event SET status = 'DEAD' WHERE id = ?", id)
    }

    /** 재시도 횟수에 대응하는 대기 시간. 마지막 값에서 멈춘다. */
    fun backoffFor(retryCount: Int): Duration =
        Duration.ofMinutes(BACKOFF_MINUTES[minOf(retryCount, BACKOFF_MINUTES.lastIndex)])

    companion object {
        /** Channex 공개 문서의 실제 재시도 스케줄. 임의 설정이 아니다. */
        val BACKOFF_MINUTES = listOf(1L, 2L, 4L, 8L, 15L, 30L)

        /** 429 를 받으면 최소 1분 중지 — Channex 공개 문서 권고. */
        const val RATE_LIMIT_MIN_WAIT_SECONDS = 60L
    }
}

/**
 * 릴레이가 보는 이벤트. **도메인 엔티티가 아니다.**
 *
 * 엔티티를 여기까지 가져오면 영속성 컨텍스트가 따라오고, 그러면 릴레이가
 * 도메인을 바꿀 수 있는 경로가 생긴다 (ADR-0008).
 */
data class PendingOutboxEvent(
    val id: Long,
    val aggregateType: String,
    val aggregateId: Long,
    val eventType: String,
    val payload: String,
    val retryCount: Int,
)

data class RelayReport(
    val published: Int = 0,
    val retried: Int = 0,
    val rateLimited: Int = 0,
    val dead: Int = 0,
) {
    val handled: Int get() = published + retried + rateLimited + dead
}
