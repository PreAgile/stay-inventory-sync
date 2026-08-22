package dev.preagile.stayinventory.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

/**
 * 확정 전 재고 선점 (ADR-0010). 결제 중에 방이 팔리는 것을 막는다.
 *
 * **이 행은 해당 `(roomTypeId, stayDate)` 의 [DailyInventory] 행 락을 보유한
 * 상태에서만 읽고 쓴다** (절대 규칙 11). 선점 잡기는 그 재고 행을 잠그지만 값을
 * 바꾸지 않아서 없어도 되는 것처럼 보인다. 지우면 집계와 INSERT 사이가 열려
 * 과선점이 생기고, `INV-4` 가 깨진 원인을 역추적하기가 매우 어렵다.
 *
 * `roomCount` 는 예약과 중복이지만 DB 가 복합 FK 로 일치를 강제한다. 유효 선점
 * 조회가 이 프로젝트에서 가장 잦은 읽기여서 비정규화를 유지했다.
 */
@Entity
@Table(name = "inventory_hold")
class InventoryHold(
    @Column(name = "room_type_id", nullable = false)
    val roomTypeId: Long,

    @Column(name = "stay_date", nullable = false)
    val stayDate: LocalDate,

    @Column(name = "reservation_id", nullable = false)
    val reservationId: Long,

    @Column(name = "room_count", nullable = false)
    val roomCount: Int,

    // 승인 대기로 넘어가면 승인 시한까지 연장된다. 그래서 var 다.
    @Column(name = "expires_at", nullable = false)
    var expiresAt: Instant,

    /** NULL 이면 살아 있다. 확정·해제 시 채운다. */
    @Column(name = "released_at")
    var releasedAt: Instant? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
) {
    /**
     * 유효 선점 판정. 두 조건이 **모두** 필요하다.
     *
     * `expiresAt` 만 보면 확정된 예약이 남은 시간 동안 자기 방을 계속 붙잡아
     * "확정이 곧 품절을 만드는" 구조가 된다.
     */
    fun isActiveAt(now: Instant): Boolean = releasedAt == null && expiresAt > now

    override fun equals(other: Any?): Boolean =
        this === other || (other is InventoryHold && id != null && id == other.id)

    override fun hashCode(): Int = javaClass.hashCode()
}
