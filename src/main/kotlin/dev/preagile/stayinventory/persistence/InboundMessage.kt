package dev.preagile.stayinventory.persistence

import dev.preagile.stayinventory.domain.InboundKind
import dev.preagile.stayinventory.domain.InboundStatus
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

/**
 * 들어온 알림 (ADR-0009 · Inbox). Outbox 의 거울상이다.
 *
 * Outbox 가 없으면 "DB 는 바뀌었는데 통보가 안 나간" 상태가 생기고, Inbox 가
 * 없으면 "알림은 왔는데 처리는 안 된" 상태가 생긴다 (절대 규칙 9).
 *
 * `payload` 는 받은 그대로 적는다. 해석하지 않는다 -- 해석에 실패해도 받은
 * 사실은 남아야 한다. 그리고 payload 의 값을 그대로 상태에 반영하지 않는다
 * (절대 규칙 10). 웹훅은 순서 보장이 없으므로 트리거로만 쓴다.
 */
@Entity
@Table(name = "inbound_message")
class InboundMessage(
    @Column(name = "channel", nullable = false, length = 32)
    val channel: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 32)
    val kind: InboundKind,

    @Column(name = "external_id", nullable = false, length = 128)
    val externalId: String,

    /**
     * NULL 을 허용한다. 순서키를 주지 않는 채널이 있고, 그것을 빈 문자열로
     * 뭉개면 "주지 않았다" 와 "빈 문자열을 주었다" 를 구분할 수 없다.
     * DB 쪽 `UNIQUE` 가 `NULLS NOT DISTINCT` 인 이유가 이 nullable 이다.
     */
    @Column(name = "sequence_key", length = 128)
    val sequenceKey: String? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false)
    val payload: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    var status: InboundStatus = InboundStatus.PENDING,

    @Column(name = "attempt_count", nullable = false)
    var attemptCount: Int = 0,

    @Column(name = "received_at", nullable = false)
    val receivedAt: Instant = Instant.now(),

    @Column(name = "processed_at")
    var processedAt: Instant? = null,

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is InboundMessage && id != null && id == other.id)

    override fun hashCode(): Int = javaClass.hashCode()
}
