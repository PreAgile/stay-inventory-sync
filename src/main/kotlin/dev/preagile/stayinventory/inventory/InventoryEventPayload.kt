package dev.preagile.stayinventory.inventory

import java.time.LocalDate

/**
 * 채널로 나갈 재고 통보의 본문.
 *
 * **사건 발생 시점의 값이다.** 릴레이가 발행하면서 재고를 다시 조회하면,
 * 재발행 사이에 낀 취소가 같은 이벤트를 다른 내용으로 내보낸다. 그것은
 * at-least-once 가 아니라 순서 없는 최신값 전송이다 (`#4` 완료 기준).
 *
 * `cause` 는 채널이 쓰지 않는다. 대사(`07-reconciliation.md`)에서 "이 값이 왜
 * 이렇게 됐는가" 를 되짚을 때 쓴다.
 */
data class InventoryChangedPayload(
    val roomTypeId: Long,
    val stayDate: LocalDate,
    val sold: Int,
    val total: Int,
    val remaining: Int,
    val cause: String,
    val reservationId: Long,
)
