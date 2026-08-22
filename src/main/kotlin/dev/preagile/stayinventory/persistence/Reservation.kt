package dev.preagile.stayinventory.persistence

import dev.preagile.stayinventory.domain.ReservationStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

/**
 * 예약.
 *
 * `channel` 이 enum 이 아니라 문자열인 이유는 `V1__init.sql` 이 그 컬럼에 `CHECK` 를
 * 걸지 않았기 때문이다. 코드가 DB 보다 좁으면 채널이 하나 늘 때마다 배포가 필요해진다.
 *
 * `channelReservationId` 는 nullable 이 아니다. `DIRECT` 채널은 내부 생성 UUID 를
 * 채운다 -- NULL 을 허용하면 `UNIQUE(channel, channel_reservation_id)` 가
 * 직접 예약에 대해 아무것도 막지 못한다.
 */
@Entity
@Table(name = "reservation")
class Reservation(
    @Column(name = "room_type_id", nullable = false)
    val roomTypeId: Long,

    /** 반개구간 `[checkIn, checkOut)`. 체크아웃 당일은 점유하지 않는다 (절대 규칙 6). */
    @Column(name = "check_in", nullable = false)
    val checkIn: LocalDate,

    @Column(name = "check_out", nullable = false)
    val checkOut: LocalDate,

    // 상태 기계가 이 값을 옮긴다. 전이 규칙은 ReservationStatus 에 데이터로 있다.
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    var status: ReservationStatus,

    /** A7. `sold` 증감 단위가 1 이 아니라 이 값이다. */
    @Column(name = "room_count", nullable = false)
    val roomCount: Int = 1,

    @Column(name = "channel", nullable = false, length = 32)
    val channel: String,

    @Column(name = "channel_reservation_id", nullable = false, length = 128)
    val channelReservationId: String,

    @Column(name = "guest_name", nullable = false, length = 200)
    val guestName: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
) {
    /** `[checkIn, checkOut)` 의 날짜들. 오름차순이다 -- 락 순서가 여기서 나온다. */
    fun stayDates(): List<LocalDate> = generateSequence(checkIn) { it.plusDays(1) }
        .takeWhile { it < checkOut }
        .toList()

    override fun equals(other: Any?): Boolean =
        this === other || (other is Reservation && id != null && id == other.id)

    override fun hashCode(): Int = javaClass.hashCode()
}
