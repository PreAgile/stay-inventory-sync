package dev.preagile.stayinventory.persistence

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate

/**
 * (룸타입 × 날짜) 격자의 키.
 *
 * 여기서는 `data class` 를 쓴다. 절대 규칙 1 이 금지하는 것은 **엔티티**의
 * `data class` 이고, 그 근거는 값 기반 `equals` 가 영속 전후로 달라진다는 것이다.
 * 복합 키는 영속 전에 값이 확정되고 그 값이 곧 동일성이므로 근거가 성립하지 않는다.
 * JPA 도 복합 키에 값 기반 `equals`/`hashCode` 를 **요구한다.**
 */
@Embeddable
data class DailyInventoryId(
    @Column(name = "room_type_id", nullable = false)
    val roomTypeId: Long,

    @Column(name = "stay_date", nullable = false)
    val stayDate: LocalDate,
) : Serializable

/**
 * (룸타입 × 날짜) 재고.
 *
 * `sold` 는 성능 최적화가 아니라 **경합의 직렬화 지점**이다. 예약 테이블을 매번
 * 집계하면 동시 예약을 막을 자리가 없다 -- 카운터가 있는 행을 잠가야
 * "잔여 1개에 100명 동시 요청" 이 직렬화된다.
 *
 * `total` 컬럼은 없다. `physicalTotal + overbookingLimit` 로 계산한다 (ADR-0007).
 */
@Entity
@Table(name = "daily_inventory")
class DailyInventory(
    @EmbeddedId
    val id: DailyInventoryId,

    @Column(name = "physical_total", nullable = false)
    var physicalTotal: Int,

    /** 0 이면 현재 동작과 수학적으로 동일하다. 되돌림이 값을 하는 자리다. */
    @Column(name = "overbooking_limit", nullable = false)
    var overbookingLimit: Int = 0,

    // var 인 유일한 이유가 이것이다. 차감·복원이 이 값을 바꾼다.
    @Column(name = "sold", nullable = false)
    var sold: Int = 0,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    val roomTypeId: Long get() = id.roomTypeId
    val stayDate: LocalDate get() = id.stayDate

    /** INV-1 의 상한. 컬럼이 아니라 계산값이다. */
    val total: Int get() = physicalTotal + overbookingLimit

    val remaining: Int get() = total - sold

    override fun equals(other: Any?): Boolean =
        this === other || (other is DailyInventory && id == other.id)

    override fun hashCode(): Int = javaClass.hashCode()
}
