package dev.preagile.stayinventory.webhook

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Inbox 를 주기적으로 소화한다.
 *
 * 스케줄러는 **깨워 주는 역할만** 한다. 정합성은 전부 [InboundMessageProcessor]
 * 의 트랜잭션 안에 있으므로, 이 컴포넌트가 죽거나 두 번 돌아도 결과가 달라지지
 * 않는다. 그래서 끄는 스위치를 두는 것이 안전하다 -- 테스트는 워커를 직접 불러
 * 시점을 통제한다.
 *
 * `fixedDelay` 다. `fixedRate` 면 처리가 주기보다 오래 걸릴 때 실행이 겹치고,
 * 그러면 같은 `PENDING` 행을 두 실행이 집는다. 지금은 처리 쪽이 상태로 막지만
 * 겹칠 이유가 없는 일을 겹치게 만들지 않는다.
 */
@Component
@ConditionalOnProperty(name = ["inbox.worker.enabled"], havingValue = "true", matchIfMissing = true)
class InboundWorkerScheduler(
    private val worker: InboundMessageWorker,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${inbox.worker.delay-ms:1000}")
    fun drain() {
        val processed = worker.processPending()
        if (processed > 0) log.info("인바운드 {}건 처리", processed)
    }
}
