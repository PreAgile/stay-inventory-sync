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

        val claimed = claimPending(limit, now)

        // 배치 안에서 같은 키의 낡은 이벤트를 먼저 걸러낸다.
        //
        // 부수 효과가 아니라 목적의 절반이다 -- 3박 예약과 그 취소가 한 배치에
        // 들어오면 같은 키에 통보가 둘 있고, 낡은 쪽을 보내 봐야 바로 덮어써진다.
        // 레이트 리밋만 태운다.
        val newestInBatch = claimed
            .filter { it.orderingKey != null }
            .groupBy { it.orderingKey }
            .mapValues { (_, events) -> events.maxOf { requireNotNull(it.version) } }

        claimed.forEach { event ->
            val key = event.orderingKey
            if (key != null) {
                val version = requireNotNull(event.version)
                // 이 배치에 더 새로운 것이 있거나, 이미 더 새로운 것이 나갔다면
                // 이 이벤트는 보낼 이유가 없다.
                val newerExists = newestInBatch.getValue(key) > version ||
                    (highestPublishedVersion(key) ?: 0L) > version
                if (newerExists) {
                    markSuperseded(event.id)
                    report = report.copy(superseded = report.superseded + 1)
                    return@forEach
                }
            }

            // 락 밖이다. 여기서 네트워크를 기다려도 재고 행은 아무도 붙잡고 있지 않다.
            //
            // 재고와 규칙은 채널 API 에서도 다른 자원이므로 경로를 나눈다.
            // payload 를 보고 분기하면 릴레이가 payload 의 구조를 알게 된다.
            val result = runCatching {
                if (event.aggregateType == AGGREGATE_CHANNEL_POLICY) {
                    adapter.pushPolicy(event.id, event.payload)
                } else {
                    adapter.push(event.id, event.payload)
                }
            }.getOrElse { ChannelSyncResult.Retryable("어댑터 예외: ${it.message}") }

            report = when (result) {
                is ChannelSyncResult.Success -> {
                    markPublished(event.id, now)
                    report.copy(published = report.published + 1)
                }

                is ChannelSyncResult.RateLimited -> {
                    // 429 는 실패가 아니라 "나중에 다시 하라" 는 신호다.
                    // 채널이 알려 준 대기 시간이 있으면 그것을 따르되 1분 하한을 지킨다.
                    val wait = maxOf(result.retryAfterSeconds ?: 0L, RATE_LIMIT_MIN_WAIT_SECONDS)
                    // **retry_count 를 올리지 않는다.** 올리면 리밋이 오래 걸린
                    // 채널의 정상 이벤트가 다섯 번 만에 DEAD 로 떨어진다 --
                    // 사장님이 예약을 많이 받았다는 이유로 통보가 죽는다.
                    // 소진 판정은 "몇 번 실패했는가" 를 세는 것이지
                    // "몇 번 미뤘는가" 를 세는 것이 아니다.
                    scheduleRetry(event, now.plusSeconds(wait), countAsFailure = false)
                    report.copy(rateLimited = report.rateLimited + 1)
                }

                is ChannelSyncResult.Retryable -> {
                    if (event.retryCount + 1 > MAX_RETRIES) {
                        // 소진. 같은 요청을 무한히 재시도하면 그 사실이 큐 길이에
                        // 묻히고, 사람이 봐야 할 것을 아무도 안 본다.
                        log.warn(
                            "재시도 소진으로 DEAD: id={} retryCount={} reason={}",
                            event.id, event.retryCount, result.reason,
                        )
                        // 마지막 실패도 센다. status 만 바꾸면 행은 DEAD 인데
                        // retry_count 는 5 로 남아, "retry_count > 5 면 DEAD" 라는
                        // 계약과 /ops/outbox/dead 가 보여 주는 실패 횟수가 어긋난다.
                        // 운영자가 그 숫자를 보고 "아직 여유가 있는데 왜 죽었나" 를 묻게 된다.
                        markDead(event.id, countAsFailure = true)
                        report.copy(dead = report.dead + 1)
                    } else {
                        scheduleRetry(event, now.plus(backoffFor(event.retryCount)))
                        report.copy(retried = report.retried + 1)
                    }
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
     * 발행 대기 이벤트를 **집어 오면서 동시에 임대(lease)한다.**
     *
     * `id` 순이다. 같은 키의 이벤트가 생성 순서대로 나가야 하고, 그 순서가
     * 백오프 때문에 깨지는 것이 `#9` 가 다루는 문제다.
     *
     * ## 왜 SELECT 가 아니라 UPDATE ... RETURNING 인가
     *
     * `#8` 은 폴링 쿼리에 `FOR UPDATE SKIP LOCKED` 를 붙이라고 적었다. 그 조각만으로는
     * 다중 인스턴스가 막히지 않는다 -- **행 락은 트랜잭션이 끝나면 풀리는데**,
     * 집기와 발행이 다른 트랜잭션이면 집자마자 락이 사라진다. 그 사이에 다른
     * 인스턴스가 같은 행을 집는다.
     *
     * 락을 발행 끝까지 쥐는 것은 더 나쁘다 -- **락을 쥔 채 외부 I/O 를 기다리게 되고**
     * (절대 규칙 4), 채널이 느린 만큼 그 행들이 묶인다.
     *
     * 그래서 **집기와 임대를 한 문장으로 접는다.** `next_attempt_at` 을 앞으로
     * 밀어 두면 그 시간 동안 다른 인스턴스의 `WHERE` 절에 걸리지 않는다.
     * 발행은 어떤 락도 쥐지 않은 상태에서 일어난다.
     *
     * ```
     * UPDATE ... SET next_attempt_at = now + LEASE
     *  WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED)   ← 여기의 락은 이 문장 동안만
     * RETURNING ...
     * ```
     *
     * `SKIP LOCKED` 는 그 짧은 구간에서도 **인스턴스끼리 서로 기다리지 않게** 한다.
     * 없으면 B 는 A 의 `UPDATE` 가 커밋될 때까지 막혀서, 인스턴스를 늘려도
     * 처리량이 늘지 않는다.
     *
     * **임대가 만료되면 그 이벤트는 다시 잡힌다.** 발행 도중 프로세스가 죽어도
     * 이벤트를 잃지 않는다 -- 그리고 그때 일어나는 재발행이 `T4` 가 다루는 것이다.
     */
    fun claimPending(limit: Int, now: Instant = Instant.now()): List<PendingOutboxEvent> =
        jdbc.query(
            """
            UPDATE outbox_event
               SET next_attempt_at = ?
             WHERE id IN (
                   SELECT id FROM outbox_event
                    WHERE status = 'PENDING' AND next_attempt_at <= ?
                    ORDER BY id
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
             )
            RETURNING id, aggregate_type, aggregate_id, event_type, payload::text, retry_count,
                      room_type_id, stay_date, version
            """.trimIndent(),
            { rs, _ ->
                PendingOutboxEvent(
                    id = rs.getLong(1),
                    aggregateType = rs.getString(2),
                    aggregateId = rs.getLong(3),
                    eventType = rs.getString(4),
                    payload = rs.getString(5),
                    retryCount = rs.getInt(6),
                    roomTypeId = rs.getObject(7) as Long?,
                    stayDate = rs.getObject(8, java.time.LocalDate::class.java),
                    version = rs.getObject(9) as Long?,
                )
            },
            java.sql.Timestamp.from(now.plus(LEASE)),
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

    /**
     * 다음 시도 시각을 미룬다.
     *
     * @param countAsFailure `retry_count` 를 올릴지. **레이트 리밋은 올리지 않는다** --
     *   소진 판정은 "몇 번 실패했는가" 이지 "몇 번 미뤘는가" 가 아니다.
     */
    fun scheduleRetry(
        event: PendingOutboxEvent,
        nextAttemptAt: Instant,
        countAsFailure: Boolean = true,
    ) {
        val increment = if (countAsFailure) 1 else 0
        jdbc.update(
            """
            UPDATE outbox_event
               SET retry_count = retry_count + ?, next_attempt_at = ?
             WHERE id = ?
            """.trimIndent(),
            increment,
            java.sql.Timestamp.from(nextAttemptAt),
            event.id,
        )
    }

    /**
     * 그 키로 **이미 나간** 가장 큰 버전. 없으면 null.
     *
     * `PUBLISHED` 만 센다. `SUPERSEDED` 는 나간 적이 없으므로 채널 상태와 무관하고,
     * `PENDING` 은 아직 나가지 않았다 -- 그것까지 세면 아직 안 나간 새 이벤트
     * 때문에 지금 나가야 할 이벤트가 건너뛰어진다.
     */
    fun highestPublishedVersion(key: OrderingKey): Long? =
        jdbc.queryForObject(
            """
            SELECT MAX(version) FROM outbox_event
             WHERE aggregate_type = ? AND room_type_id = ? AND stay_date = ?
               AND status = 'PUBLISHED'
            """.trimIndent(),
            Long::class.javaObjectType,
            key.aggregateType,
            key.roomTypeId,
            key.stayDate,
        )

    /** 낡아서 보내지 않았다. 발행한 적 없으므로 `PUBLISHED` 로 적지 않는다. */
    fun markSuperseded(id: Long) {
        jdbc.update("UPDATE outbox_event SET status = 'SUPERSEDED' WHERE id = ?", id)
    }

    /**
     * 영구 실패로 보낸다.
     *
     * @param countAsFailure 마지막 시도를 `retry_count` 에 반영할지.
     *   **소진으로 죽을 때만 참이다.** `Permanent`(4xx)는 한 번의 응답으로 판정한
     *   것이라 재시도 예산과 무관하고, 함께 올리면 "다섯 번 실패했다" 로 읽힌다.
     */
    fun markDead(id: Long, countAsFailure: Boolean = false) {
        val increment = if (countAsFailure) 1 else 0
        jdbc.update(
            "UPDATE outbox_event SET status = 'DEAD', retry_count = retry_count + ? WHERE id = ?",
            increment,
            id,
        )
    }

    /** 재시도 횟수에 대응하는 대기 시간. 마지막 값에서 멈춘다. */
    fun backoffFor(retryCount: Int): Duration =
        Duration.ofMinutes(BACKOFF_MINUTES[minOf(retryCount, BACKOFF_MINUTES.lastIndex)])

    companion object {
        /**
         * 정책 통보의 `aggregate_type`.
         *
         * 문자열을 릴레이가 직접 아는 것이 마음에 들지는 않지만, 그 대안은
         * 릴레이가 `policy` 패키지에 의존하는 것이다 -- 그러면 릴레이가
         * 도메인 서비스에 닿고 ArchUnit 경계가 무너진다.
         */
        const val AGGREGATE_CHANNEL_POLICY = "CHANNEL_POLICY"

        /** Channex 공개 문서의 실제 재시도 스케줄. 임의 설정이 아니다. */
        val BACKOFF_MINUTES = listOf(1L, 2L, 4L, 8L, 15L, 30L)

        /** 429 를 받으면 최소 1분 중지 — Channex 공개 문서 권고. */
        const val RATE_LIMIT_MIN_WAIT_SECONDS = 60L

        /**
         * 이 횟수를 넘게 **실패**하면 DEAD 로 보낸다.
         *
         * 백오프 표가 여섯 칸이므로 마지막 간격(30분)까지 가 본 뒤 포기하는 셈이다.
         * 무한 재시도를 두지 않는 이유는 그 이벤트가 영원히 큐에 남아 **큐 길이가
         * 지표로서 의미를 잃기** 때문이다.
         */
        const val MAX_RETRIES = 5

        /**
         * 임대 기간. 이 시간 안에 발행이 끝나지 않으면 다른 인스턴스가 다시 집는다.
         *
         * 짧으면 느린 채널 응답을 기다리는 동안 중복 발행이 늘고, 길면 죽은
         * 인스턴스가 쥐고 있던 이벤트가 그만큼 늦게 나간다. 채널 호출 타임아웃보다
         * 넉넉하게 잡되 백오프 최소값(1분)과 같은 자리에 둔다.
         */
        val LEASE: Duration = Duration.ofMinutes(1)
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
    val roomTypeId: Long? = null,
    val stayDate: java.time.LocalDate? = null,
    val version: Long? = null,
) {
    /**
     * 순서 판정의 키. 셋 다 있거나 셋 다 없다 (DB CHECK).
     *
     * **`aggregateType` 이 키에 들어간다.** 재고 통보와 정책 통보는 같은
     * (룸타입, 날짜) 격자를 쓰지만 서로를 낡게 만들면 안 된다 -- 캡을 바꿨다고
     * 재고 통보가 건너뛰어지면 채널은 잔여를 영영 모른다.
     */
    val orderingKey: OrderingKey?
        get() = if (roomTypeId != null && stayDate != null && version != null) {
            OrderingKey(aggregateType, roomTypeId, stayDate)
        } else {
            null
        }
}

/** 순서를 비교할 단위. 이 셋이 같은 이벤트끼리만 낡음을 따진다. */
data class OrderingKey(
    val aggregateType: String,
    val roomTypeId: Long,
    val stayDate: java.time.LocalDate,
)

data class RelayReport(
    val published: Int = 0,
    val retried: Int = 0,
    val rateLimited: Int = 0,
    val dead: Int = 0,
    /** 낡아서 보내지 않은 건수. 이 숫자가 곧 아낀 채널 호출이다. */
    val superseded: Int = 0,
) {
    val handled: Int get() = published + retried + rateLimited + dead + superseded
}
