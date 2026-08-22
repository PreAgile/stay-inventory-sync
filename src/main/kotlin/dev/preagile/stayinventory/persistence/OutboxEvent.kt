package dev.preagile.stayinventory.persistence

import dev.preagile.stayinventory.domain.OutboxStatus
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate

/**
 * 나갈 통보 (ADR-0003).
 *
 * 이 행의 INSERT 는 도메인 변경과 **같은 트랜잭션**이다 (절대 규칙 3). 분리되면
 * dual-write 문제가 그대로 재발하며, 그것이 이 저장소의 존재 이유다.
 *
 * `payload` 는 발행 시점의 값이 아니라 **사건 발생 시점의 값**이다. 릴레이가
 * 발행하면서 재고를 다시 조회하면 재발행 사이에 낀 취소가 같은 이벤트를 다른
 * 내용으로 내보내고, 그것은 at-least-once 가 아니라 순서 없는 최신값 전송이다.
 */
@Entity
@Table(name = "outbox_event")
class OutboxEvent(
    @Column(name = "aggregate_type", nullable = false, length = 32)
    val aggregateType: String,

    @Column(name = "aggregate_id", nullable = false)
    val aggregateId: Long,

    @Column(name = "event_type", nullable = false, length = 64)
    val eventType: String,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    val payload: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: OutboxStatus = OutboxStatus.PENDING,

    @Column(name = "retry_count", nullable = false)
    var retryCount: Int = 0,

    @Column(name = "next_attempt_at", nullable = false)
    var nextAttemptAt: Instant = Instant.now(),

    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "published_at")
    var publishedAt: Instant? = null,

    /**
     * 재고 통보의 키 절반. `aggregateId` 를 재사용하지 않는 이유는 그것이
     * 다형 참조이고 `BIGINT` 하나라 (룸타입, 날짜) 복합 키를 담을 수 없기 때문이다.
     */
    @Column(name = "room_type_id")
    val roomTypeId: Long? = null,

    @Column(name = "stay_date")
    val stayDate: LocalDate? = null,

    /**
     * **같은 키 안에서** 단조 증가한다. 전역 순서가 아니다.
     *
     * 단조성을 보장하는 것은 시퀀스가 아니라 **`daily_inventory` 행 락**이다.
     * 같은 키의 통보를 만드는 모든 트랜잭션이 그 행을 잠그고 지나가므로,
     * `MAX(version) + 1` 을 읽고 쓰는 사이에 다른 트랜잭션이 끼어들 수 없다.
     * 재고 카운터를 직렬화하는 락이 버전도 함께 직렬화한다 (ADR-0012).
     */
    @Column(name = "version")
    val version: Long? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is OutboxEvent && id != null && id == other.id)

    override fun hashCode(): Int = javaClass.hashCode()
}
