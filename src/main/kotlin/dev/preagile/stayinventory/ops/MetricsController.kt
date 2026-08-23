package dev.preagile.stayinventory.ops

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * 지표 3덩어리. 계획(`B5`)은 2종이었고 구현에서 backlog 가 늘었다 --
 * 발행된 것의 지연 분포만으로는 **영원히 안 나가는 이벤트가 쌓일 때 분포가
 * 오히려 좋아 보인다.** 나간 것의 지연과 못 나간 것의 개수는 다른 질문이다.
 *
 * ```
 * overbooking_prevented_total   재고 부족으로 거절된 요청 수
 * outbox_publish_lag_seconds    created_at 부터 published_at 까지의 분포
 * ```
 *
 * ## 왜 Micrometer 가 아닌가
 *
 * 관측 인프라가 없는 환경에서 Micrometer 는 **의존성만 늘리고 아무것도 보여 주지
 * 않는다.** 수집기가 붙기 전까지 그 지표는 프로세스 메모리 안에서만 존재하고,
 * 그것을 보려면 어차피 엔드포인트가 하나 필요하다.
 *
 * 붙일 때가 되면 이 두 값은 그대로 게이지·히스토그램에 매핑된다 -- 지표의
 * **정의**를 여기서 확정해 두는 것이 이 슬라이스의 목적이고, 정의가 확정되면
 * 전달 수단은 바꿔 끼우면 된다.
 *
 * ## 두 지표의 성격이 다르다
 *
 * | 지표 | 출처 | 재기동하면 |
 * |---|---|---|
 * | 오버부킹 차단 | 인메모리 카운터 | **0 으로 돌아간다** |
 * | 발행 지연 | `outbox_event` 집계 | 그대로다 |
 *
 * 이 차이를 응답에서 숨기지 않는다. 차단 건수에는 `since` 가 함께 나가고,
 * 그것 없이 읽으면 반드시 오해한다.
 */
@RestController
@RequestMapping("/ops/metrics")
class MetricsController(
    private val jdbc: JdbcTemplate,
    private val overbookingPrevented: OverbookingPreventedCounter,
) {

    @GetMapping
    fun metrics(): MetricsSnapshot = MetricsSnapshot(
        overbookingPrevented = OverbookingPreventedMetric(
            total = overbookingPrevented.total,
            since = overbookingPrevented.since,
        ),
        outboxPublishLag = publishLag(),
        outboxBacklog = backlog(),
    )

    /**
     * 발행 지연 분포.
     *
     * 평균을 내지 않는다. **재고 통보의 지연은 꼬리가 문제다** -- 대부분이 1초
     * 안에 나가도 백오프에 걸린 소수가 30분씩 밀리고, 오버부킹은 그 소수에서
     * 나온다. 평균은 그 소수를 지운다.
     *
     * `SUPERSEDED` 는 세지 않는다. 나간 적이 없으므로 지연이라는 개념이 없다 --
     * 세면 "0초에 처리됨" 으로 잡혀 분포가 실제보다 좋아 보인다.
     */
    private fun publishLag(): PublishLagMetric =
        jdbc.query(
            """
            SELECT count(*),
                   COALESCE(percentile_cont(0.5)  WITHIN GROUP (
                       ORDER BY EXTRACT(EPOCH FROM (published_at - created_at))), 0),
                   COALESCE(percentile_cont(0.95) WITHIN GROUP (
                       ORDER BY EXTRACT(EPOCH FROM (published_at - created_at))), 0),
                   COALESCE(MAX(EXTRACT(EPOCH FROM (published_at - created_at))), 0)
              FROM outbox_event
             WHERE status = 'PUBLISHED' AND published_at IS NOT NULL
            """.trimIndent(),
        ) { rs, _ ->
            PublishLagMetric(
                samples = rs.getLong(1),
                p50Seconds = rs.getDouble(2),
                p95Seconds = rs.getDouble(3),
                maxSeconds = rs.getDouble(4),
            )
        }.first()

    /**
     * 아직 안 나간 통보.
     *
     * 지연 분포는 **이미 나간 것**만 본다. 영원히 안 나가는 이벤트가 쌓이면
     * 그 분포는 오히려 좋아 보인다 -- 밀린 것이 표본에서 빠지기 때문이다.
     * 그래서 대기 건수와 **가장 오래된 것의 나이**를 함께 낸다.
     */
    private fun backlog(): BacklogMetric =
        jdbc.query(
            """
            SELECT count(*) FILTER (WHERE status = 'PENDING'),
                   count(*) FILTER (WHERE status = 'DEAD'),
                   count(*) FILTER (WHERE status = 'SUPERSEDED'),
                   COALESCE(MAX(EXTRACT(EPOCH FROM (now() - created_at)))
                            FILTER (WHERE status = 'PENDING'), 0)
              FROM outbox_event
            """.trimIndent(),
        ) { rs, _ ->
            BacklogMetric(
                pending = rs.getLong(1),
                dead = rs.getLong(2),
                superseded = rs.getLong(3),
                oldestPendingAgeSeconds = rs.getDouble(4),
            )
        }.first()
}

data class MetricsSnapshot(
    val overbookingPrevented: OverbookingPreventedMetric,
    val outboxPublishLag: PublishLagMetric,
    val outboxBacklog: BacklogMetric,
)

/** [since] 없이 [total] 만 읽으면 반드시 오해한다. 그래서 한 덩어리로 묶는다. */
data class OverbookingPreventedMetric(val total: Long, val since: Instant)

data class PublishLagMetric(
    val samples: Long,
    val p50Seconds: Double,
    val p95Seconds: Double,
    val maxSeconds: Double,
)

data class BacklogMetric(
    val pending: Long,
    val dead: Long,
    val superseded: Long,
    val oldestPendingAgeSeconds: Double,
)
