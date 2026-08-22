package dev.preagile.stayinventory.inventory

import java.time.LocalDate

/**
 * 예약 요청. 채널이 무엇이든 이 형태로 들어온다.
 *
 * `channelReservationId` 는 nullable 이 아니다. 직접 예약이면 호출부가 내부
 * UUID 를 채운다 -- NULL 을 허용하면 `UNIQUE(channel, channel_reservation_id)`
 * 가 직접 예약에 대해 아무것도 막지 못한다.
 */
data class ReserveCommand(
    val roomTypeId: Long,
    val checkIn: LocalDate,
    val checkOut: LocalDate,
    val roomCount: Int,
    val channel: String,
    val channelReservationId: String,
    val guestName: String,
) {
    init {
        // INV-3 을 DB 에 닿기 전에 거른다. DB CHECK 가 최종 방어선이고
        // 이쪽은 400 과 500 을 가르기 위한 것이다.
        require(checkIn < checkOut) { "체크아웃은 체크인보다 뒤여야 한다: $checkIn ~ $checkOut" }
        require(roomCount > 0) { "객실 수는 1 이상이어야 한다: $roomCount" }
    }

    /**
     * `[checkIn, checkOut)` 의 날짜들을 **오름차순으로** 준다.
     *
     * 정렬이 여기 들어 있는 것이 `T2` 방어의 전부다. 호출부에서 `sorted()` 를
     * 부르는 구조면 그 한 줄이 빠질 자리가 생긴다 (절대 규칙 2).
     */
    fun stayDates(): List<LocalDate> = generateSequence(checkIn) { it.plusDays(1) }
        .takeWhile { it < checkOut }
        .toList()
}

/**
 * 왜 그 날짜를 팔 수 없는가.
 *
 * `NO_GRID` 를 `SOLD_OUT` 과 합치지 않는다. 매진은 정상 운영이고, 격자 없음은
 * **재고를 열지 않은 날짜에 요청이 들어왔다**는 운영 신호다. 하나로 뭉치면
 * 재고를 안 연 것이 매진으로 보고되어 아무도 알아채지 못한다.
 */
enum class UnavailableReason { SOLD_OUT, NO_GRID }

data class Unavailable(
    val stayDate: LocalDate,
    val reason: UnavailableReason,
    val remaining: Int?,
)

/**
 * 부분 성공은 없다. 전 날짜가 되거나 전 날짜가 안 되거나 둘 중 하나다 --
 * 3박 중 2박만 잡힌 예약은 손님에게도 숙소에게도 쓸모가 없다.
 */
sealed interface ReserveResult {
    data class Reserved(val reservationId: Long) : ReserveResult

    data class Rejected(val unavailable: List<Unavailable>) : ReserveResult
}
