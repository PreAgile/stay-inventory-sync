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
 *
 * ## 인스턴스 하나만 한 건을 집는다 (`#66`)
 *
 * 릴레이에는 집기-임대가 있고 여기에는 없었다. **그 비대칭에 근거가 없었다.**
 *
 * 없어도 중복 **처리**는 막혔다 -- 조기 반환과 `reservation` 의 `UNIQUE` 가
 * 막는다. 막히지 않은 것은 중복 **시도**이고, 그때 한쪽 트랜잭션은 예외로
 * 롤백된 뒤 `runCatching` 이 삼킨다. **안전하지만 조용히 낭비했다.**
 *
 * 낭비보다 나쁜 것은 **릴레이와 다른 규율을 쓰는 것**이다 -- 다음 사람이 어느
 * 쪽을 표준으로 볼지 알 수 없다. 저장소가 이미 배운 패턴을 적용한다.
 */
@Service
class InboundMessageWorker(
    private val inbound: InboundMessageRepository,
    private val processor: InboundMessageProcessor,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 대기 중인 알림을 **집어서** 처리하고 처리된 건수를 준다. */
    fun processPending(limit: Int = 100): Int =
        inbound.claimPending(limit, LEASE_SECONDS).count { message ->
            runCatching { processor.processOne(requireNotNull(message.id)) }
                .onFailure { log.warn("인바운드 처리 실패: id={}", message.id, it) }
                .getOrDefault(ProcessOutcome.FAILED) != ProcessOutcome.FAILED
        }

    companion object {
        /**
         * 임대 길이.
         *
         * 한 건 처리는 도메인 트랜잭션 하나이므로 짧다. 릴레이(1분)보다 짧게 잡는
         * 이유는 **외부 호출이 없기 때문**이다 -- 죽은 인스턴스가 남긴 임대만큼
         * 그 알림의 처리가 늦어지므로 필요 이상으로 길게 잡을 이유가 없다.
         */
        const val LEASE_SECONDS = 30
    }
}
