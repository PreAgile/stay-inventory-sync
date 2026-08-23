package dev.preagile.stayinventory.webhook

import com.fasterxml.jackson.databind.ObjectMapper
import dev.preagile.stayinventory.domain.InboundStatus
import dev.preagile.stayinventory.inventory.CancelResult
import dev.preagile.stayinventory.inventory.InventoryService
import dev.preagile.stayinventory.inventory.ReserveCommand
import dev.preagile.stayinventory.inventory.ReserveResult
import dev.preagile.stayinventory.persistence.InboundMessage
import dev.preagile.stayinventory.persistence.InboundMessageRepository
import dev.preagile.stayinventory.persistence.ReservationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 알림 **한 건**을 도메인에 반영한다.
 *
 * **`status = PROCESSED` 를 도메인 변경과 같은 트랜잭션에서 쓴다.** 이것이 Inbox
 * 의 전부이며 Outbox 의 논증과 완전히 같다 (ADR-0003 · 절대 규칙 9).
 *
 * ```
 * 외부 큐   :  처리 -> 확인.  두 단계라서 순서가 문제가 된다
 * Inbox     :  처리와 표시가 한 트랜잭션.  순서라는 개념이 없다
 * ```
 *
 * 확인을 먼저 하고 롤백되면 그 알림은 영구히 사라진다 --
 * **유실과 중복 중 중복을 택한다.**
 */
@Service
class InboundMessageProcessor(
    private val inbound: InboundMessageRepository,
    private val reservations: ReservationRepository,
    private val inventoryService: InventoryService,
    private val objectMapper: ObjectMapper,
) {

    @Transactional
    fun processOne(inboundMessageId: Long): ProcessOutcome {
        val message = inbound.findById(inboundMessageId).orElseThrow {
            IllegalStateException("인바운드 $inboundMessageId 이 없다")
        }
        // 이미 소화된 알림. 워커 두 대가 같은 행을 집어도 두 번 반영되지 않는다.
        if (message.status != InboundStatus.PENDING) return ProcessOutcome.IGNORED

        val webhook = objectMapper.readValue(message.payload, ReservationWebhook::class.java)
        message.attemptCount += 1

        val outcome = when (webhook.event) {
            WebhookEvent.RESERVATION_CREATED -> handleCreated(message, webhook)
            WebhookEvent.RESERVATION_CANCELED -> handleCanceled(message, webhook)
        }

        message.status = when (outcome) {
            ProcessOutcome.PROCESSED -> InboundStatus.PROCESSED
            ProcessOutcome.IGNORED -> InboundStatus.IGNORED
            // 다시 잡히도록 PENDING 으로 둔다. 실패를 여기서 삼키면 알림이 사라진다.
            ProcessOutcome.FAILED -> InboundStatus.PENDING
        }
        if (outcome != ProcessOutcome.FAILED) message.processedAt = Instant.now()
        inbound.save(message)

        return outcome
    }

    private fun handleCreated(
        message: InboundMessage,
        webhook: ReservationWebhook,
    ): ProcessOutcome {
        // 멱등의 두 번째 방어선 -- 이미 그 예약이 있으면 차감하지 않는다.
        //
        // inbound_message UNIQUE 가 같은 **알림**의 재처리를 막는다면, 이쪽은
        // 같은 **예약**에 대한 다른 알림(순서키가 달라 UNIQUE 를 통과한 재전송)을
        // 막는다. 둘은 다른 것을 막으므로 하나가 다른 하나를 대신하지 못한다.
        val existing = reservations.findByChannelAndChannelReservationId(
            message.channel,
            webhook.channelReservationId,
        )
        if (existing != null) return ProcessOutcome.IGNORED

        // 묘비 검사 (ADR-0013). 이 예약에 **더 높은 rank 의 취소가 이미 기록돼
        // 있으면** 이 생성은 낡은 것이다.
        //
        // 정렬은 한 배치 안에서만 순서를 정한다. 취소와 생성이 다른 폴링 주기에
        // 도착하면 정렬이 개입할 자리가 없고, 그때 늦게 온 생성이 예약을 확정해
        // **최신 취소가 사라진다** -- 팔리지 않아야 하는 방이 팔린 채 남는다.
        //
        // rank 가 null 이면 비교할 수 없으므로 검사하지 않는다. 그 채널의 한계는
        // drift 검출이 받는다.
        val rank = message.sequenceRank
        if (rank != null &&
            inbound.hasLaterCancel(message.channel, webhook.channelReservationId, rank)
        ) {
            return ProcessOutcome.IGNORED
        }

        val command = ReserveCommand(
            roomTypeId = requireNotNull(webhook.roomTypeId) { "roomTypeId 가 없다" },
            checkIn = requireNotNull(webhook.checkIn) { "checkIn 이 없다" },
            checkOut = requireNotNull(webhook.checkOut) { "checkOut 이 없다" },
            roomCount = webhook.roomCount ?: 1,
            channel = message.channel,
            channelReservationId = webhook.channelReservationId,
            guestName = webhook.guestName ?: "이름 없음",
        )

        return when (inventoryService.reserve(command)) {
            is ReserveResult.Reserved -> ProcessOutcome.PROCESSED

            // 이미 있는 예약. 조기 조회가 잡았다 -- 여기 오는 것은 정상이다.
            is ReserveResult.Duplicate -> ProcessOutcome.IGNORED

            // 재고가 없어서 못 받는다. **재시도해도 결과가 같으므로 IGNORED 다.**
            //
            // 여기가 이 시스템이 어긋남을 인정하는 지점이다 -- 채널은 예약을 이미
            // 확정했는데 우리 재고가 없다. 조용히 재시도하면 그 어긋남이 큐 안에
            // 숨으므로, 기록으로 남기고 대사(#6)에 넘긴다.
            is ReserveResult.Rejected -> ProcessOutcome.IGNORED
        }
    }

    private fun handleCanceled(
        message: InboundMessage,
        webhook: ReservationWebhook,
    ): ProcessOutcome {
        // 없는 예약의 취소. 생성 알림이 아직 안 왔거나 순서가 뒤집혔다.
        // PENDING 으로 남기면 무한 재시도가 되므로 기록만 남기고 대사에 넘긴다.
        val existing = reservations.findByChannelAndChannelReservationId(
            message.channel,
            webhook.channelReservationId,
        ) ?: return ProcessOutcome.IGNORED

        return when (inventoryService.cancel(requireNotNull(existing.id))) {
            is CancelResult.Restored -> ProcessOutcome.PROCESSED
            // 이미 취소됨 · 없음 -- 둘 다 더 할 일이 없다
            else -> ProcessOutcome.IGNORED
        }
    }
}
