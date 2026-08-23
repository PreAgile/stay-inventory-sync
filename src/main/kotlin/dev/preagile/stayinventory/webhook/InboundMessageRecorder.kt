package dev.preagile.stayinventory.webhook

import com.fasterxml.jackson.databind.ObjectMapper
import dev.preagile.stayinventory.channel.ChannelAdapter
import dev.preagile.stayinventory.domain.InboundKind
import dev.preagile.stayinventory.persistence.InboundMessage
import dev.preagile.stayinventory.persistence.InboundMessageRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service

/**
 * 수신 1단계 -- **빠르게, 얕게** 적고 끝낸다 (`07-reconciliation.md`).
 *
 * 여기서 도메인을 건드리지 않는다. 받은 그대로 적고 즉시 2xx 를 준 뒤,
 * 별도 워커가 처리한다 (절대 규칙 9). 그래야 채널의 재시도 시계와 우리 처리
 * 시간이 분리된다 -- 처리하다 느려지면 채널은 실패로 보고 같은 알림을 또 보낸다.
 *
 * ## 트랜잭션 경계에 대하여
 *
 * 이 클래스에 `@Transactional` 을 붙이지 않는다. 붙이면 `save` 가 던진
 * 제약 위반을 **같은 트랜잭션 안에서** 잡게 되고, 그 트랜잭션은 이미
 * rollback-only 로 표시돼 있어 커밋 시점에 다시 터진다. 중복을 흡수하려면
 * 예외를 **트랜잭션 밖에서** 받아야 한다.
 */
@Service
class InboundMessageRecorder(
    private val inbound: InboundMessageRepository,
    private val objectMapper: ObjectMapper,
    private val adapters: List<ChannelAdapter>,
) {

    fun record(channel: String, webhook: ReservationWebhook): RecordOutcome {
        // ① 애플리케이션 조기 반환. 흔한 중복을 예외 없이 걸러낸다.
        //    이것만으로는 부족하다 -- 읽기와 쓰기 사이에 다른 요청이 들어온다.
        if (inbound.alreadyReceived(
                channel,
                webhook.channelReservationId,
                webhook.event.name,
                webhook.sequenceKey,
            )
        ) {
            return RecordOutcome.Duplicate
        }

        return try {
            val saved = inbound.save(
                InboundMessage(
                    channel = channel,
                    kind = InboundKind.BOOKING,
                    externalId = webhook.channelReservationId,
                    sequenceKey = webhook.sequenceKey,
                    // 정규화는 **기록 시점**에 한다. 처리 시점에 하면 정렬이
                    // 정규화 결과를 쓸 수 없다 -- 정렬이 조회에 있기 때문이다.
                    sequenceRank = rankFor(channel, webhook.sequenceKey),
                    eventType = webhook.event.name,
                    // 받은 그대로 적는다. 해석에 실패해도 받은 사실은 남아야 한다.
                    payload = objectMapper.writeValueAsString(webhook),
                ),
            )
            RecordOutcome.Accepted(requireNotNull(saved.id) { "저장 직후 id 가 없다" })
        } catch (e: DataIntegrityViolationException) {
            // ② DB UNIQUE NULLS NOT DISTINCT. ① 이 경합으로 뚫린 경우가 여기 온다.
            //    두 요청이 동시에 ① 을 통과할 수 있고, 그때 막는 것은 DB 뿐이다.
            RecordOutcome.Duplicate
        }
    }

    /**
     * 순서키를 비교 가능한 값으로 바꾼다 (ADR-0013).
     *
     * 그 채널의 어댑터가 없으면 `null` 이다 -- 형식을 아는 주체가 없으므로
     * 추측하지 않는다. 순서를 복원할 수 없다는 사실이 그대로 남는다.
     */
    private fun rankFor(channel: String, sequenceKey: String?): Long? =
        adapters.firstOrNull { it.channel == channel }?.sequenceRank(sequenceKey)
            ?: adapters.firstOrNull()?.sequenceRank(sequenceKey)
}
