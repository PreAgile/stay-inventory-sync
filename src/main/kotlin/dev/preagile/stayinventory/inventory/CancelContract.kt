package dev.preagile.stayinventory.inventory

/**
 * 취소 결과.
 *
 * `AlreadyCanceled` 는 **실패가 아니다.** 중복 취소 웹훅은 예외가 아니라 계약의
 * 일부이고, 멱등하게 처리했다면 그것은 성공이다 (절대 규칙 5). 여기서 4xx 를
 * 주면 채널이 실패로 간주해 최대 24시간 재시도한다.
 *
 * `NotFound` 와 구분하는 이유는 둘의 운영 의미가 다르기 때문이다 -- 없는 예약을
 * 취소하려는 요청은 우리 쪽이나 채널 쪽 데이터가 어긋났다는 신호이고, 이미 취소된
 * 예약에 대한 요청은 정상적인 재시도다. 뭉치면 진짜 이상이 재시도 노이즈에 묻힌다.
 */
sealed interface CancelResult {
    data class Restored(val reservationId: Long, val roomCount: Int) : CancelResult

    object AlreadyCanceled : CancelResult

    object NotFound : CancelResult
}
