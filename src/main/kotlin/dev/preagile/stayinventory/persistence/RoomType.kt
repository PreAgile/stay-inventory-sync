package dev.preagile.stayinventory.persistence

import dev.preagile.stayinventory.domain.BookingMode
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 룸타입.
 *
 * `propertyId` 는 [Property] 참조가 아니라 **ID 값**이다. 연관 매핑을 두면
 * dirty checking 이 `InventoryService` 도 Repository 도 거치지 않고 UPDATE 를
 * 만들어, ArchUnit 이 그 쓰기를 잡지 못한다 (ADR-0008 · 절대 규칙 12).
 */
@Entity
@Table(name = "room_type")
class RoomType(
    @Column(name = "property_id", nullable = false)
    val propertyId: Long,

    @Column(name = "name", nullable = false, length = 200)
    val name: String,

    @Column(name = "capacity", nullable = false)
    val capacity: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "booking_mode", nullable = false, length = 16)
    val bookingMode: BookingMode = BookingMode.INSTANT,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is RoomType && id != null && id == other.id)

    override fun hashCode(): Int = javaClass.hashCode()
}
