package dev.preagile.stayinventory.webhook

import java.time.LocalDate

/**
 * 채널이 보내는 예약 알림.
 *
 * **이 값을 그대로 상태에 반영하지 않는다** (절대 규칙 10). 채널매니저 공개
 * 문서가 웹훅의 순서 보장이 없다고 명시하므로, payload 는 트리거이고 순서 판정은
 * [sequenceKey] 가 한다. 순서 키조차 없는 채널은 순서를 복원할 수 없으며
 * **그 사실 자체가 설계 정보다** -- 그 채널은 drift 검출에 더 의존해야 한다
 * (`docs/07-reconciliation.md`).
 */
data class ReservationWebhook(
    val event: WebhookEvent,
    val channelReservationId: String,
    /** 채널이 주는 리비전 번호나 시각. 주지 않는 채널은 null 이다. 빈 문자열이 아니다. */
    val sequenceKey: String? = null,
    val roomTypeId: Long? = null,
    val checkIn: LocalDate? = null,
    val checkOut: LocalDate? = null,
    val roomCount: Int? = null,
    val guestName: String? = null,
)

enum class WebhookEvent { RESERVATION_CREATED, RESERVATION_CANCELED }

/**
 * 수신 결과. **둘 다 2xx 다.**
 *
 * `Duplicate` 는 실패가 아니다. at-least-once 에서 중복은 예외가 아니라 계약의
 * 일부이고, 4xx 를 주면 채널이 실패로 간주해 최대 24시간 재시도한다 (절대 규칙 5).
 */
sealed interface RecordOutcome {
    data class Accepted(val inboundMessageId: Long) : RecordOutcome

    object Duplicate : RecordOutcome
}

/**
 * 처리 결과. 워커가 알림 하나를 소화한 뒤의 판정이다.
 *
 * `Ignored` 는 **정상**이다. 이미 존재하는 예약에 대한 생성 알림이 그렇고,
 * 그것이 멱등의 두 번째 방어선이다.
 */
enum class ProcessOutcome { PROCESSED, IGNORED, FAILED }
