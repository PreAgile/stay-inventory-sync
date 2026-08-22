package dev.preagile.stayinventory.webhook

import dev.preagile.stayinventory.persistence.InboundMessageRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 수신 2단계 -- Inbox 를 소화한다.
 *
 * **처리 본체는 [InboundMessageProcessor] 에 있다.** 같은 빈 안에 두면
 * `processPending` 이 `processOne` 을 **자기 호출**하게 되고, 그때는 프록시를
 * 거치지 않아 `@Transactional` 이 통째로 무시된다. 트랜잭션이 없으면
 * "도메인 변경과 처리 표시가 한 커밋" 이라는 이 구조의 전부가 사라지는데,
 * **테스트는 통과한다** -- 단건 처리에서는 각 리포지토리 호출이 자기 트랜잭션을
 * 열어 결과가 같아 보이기 때문이다. 빈을 나눠 그 경로 자체를 없앤다.
 *
 * 한 건의 실패가 나머지를 막지 않는다. 실패한 건은 `PENDING` 으로 남아 다음
 * 회차에 다시 잡힌다 -- 한 건 때문에 큐 전체가 서면 그것이 곧 장애다.
 */
@Service
class InboundMessageWorker(
    private val inbound: InboundMessageRepository,
    private val processor: InboundMessageProcessor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 대기 중인 알림을 처리하고 **처리된 건수**를 준다. */
    fun processPending(limit: Int = 100): Int =
        inbound.findPending(limit).count { message ->
            runCatching { processor.processOne(requireNotNull(message.id)) }
                .onFailure { log.warn("인바운드 처리 실패: id={}", message.id, it) }
                .getOrDefault(ProcessOutcome.FAILED) != ProcessOutcome.FAILED
        }
}
