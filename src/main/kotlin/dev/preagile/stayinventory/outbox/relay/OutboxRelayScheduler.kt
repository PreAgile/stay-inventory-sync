package dev.preagile.stayinventory.outbox.relay

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 릴레이를 주기적으로 깨운다. Inbox 워커와 같은 구조다.
 *
 * 스케줄러가 정합성을 담당하지 않는다 -- 이 컴포넌트가 죽으면 통보가 늦어질 뿐
 * 데이터는 어긋나지 않는다. `outbox_event` 가 사실을 들고 있기 때문이다.
 *
 * `fixedDelay` 다. `fixedRate` 면 발행이 주기보다 오래 걸릴 때 실행이 겹치고,
 * 겹치면 같은 `PENDING` 행을 두 실행이 집는다 -- `#8` 의 `SKIP LOCKED` 가
 * 오기 전까지는 그 상황을 막을 수단이 없다.
 */
@Component
@ConditionalOnProperty(name = ["outbox.relay.enabled"], havingValue = "true", matchIfMissing = true)
class OutboxRelayScheduler(
    private val relay: OutboxRelay,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${outbox.relay.delay-ms:1000}")
    fun drain() {
        val report = relay.drain()
        if (report.handled > 0) log.info("Outbox 처리: {}", report)
    }
}
