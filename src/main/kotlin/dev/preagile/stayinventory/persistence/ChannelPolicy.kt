package dev.preagile.stayinventory.persistence

import dev.preagile.stayinventory.domain.ChannelPolicyKind
import dev.preagile.stayinventory.domain.ChannelPolicySource
import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.EnumType
import jakarta.persistence.Entity
import jakarta.persistence.Enumerated
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant
import java.time.LocalDate

/**
 * 정책 장부의 키. `kind` 까지 넣어 4개다 -- 알림 하나가 `kind` 를 여러 개 실어
 * 오므로 (룸타입, 날짜, 채널) 셋으로는 행이 특정되지 않는다.
 */
@Embeddable
data class ChannelPolicyId(
    @Column(name = "room_type_id", nullable = false)
    val roomTypeId: Long,

    @Column(name = "stay_date", nullable = false)
    val stayDate: LocalDate,

    @Column(name = "channel", nullable = false, length = 32)
    val channel: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    val kind: ChannelPolicyKind,
) : Serializable

/**
 * 채널별 노출 규칙 장부 (ADR-0009).
 *
 * 재고 축과 정책 축을 가르기 위한 테이블이다. 채널이 무언가를 바꿨을 때
 * "재고를 바꿨다" 와 "우리가 그 채널에 걸어 둔 규칙을 바꿨다" 를 구분하지 못하면
 * 재고 축의 원본이 둘이 된다.
 *
 * `daily_inventory` 와 격자를 공유하지만 FK 는 걸지 않는다. 재고 행이 아직 없는
 * 미래 날짜에 노출 상한을 미리 설정하는 것이 캡형 운영에서 실제로 필요하다.
 */
@Entity
@Table(name = "channel_policy")
class ChannelPolicy(
    @EmbeddedId
    val id: ChannelPolicyId,

    /** `CLOSED` 는 NULL, `CAP`·`OFFSET` 은 값이 있어야 한다. DB `CHECK` 가 강제한다. */
    @Column(name = "value")
    var value: Int? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    var source: ChannelPolicySource,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    val roomTypeId: Long get() = id.roomTypeId
    val stayDate: LocalDate get() = id.stayDate
    val channel: String get() = id.channel
    val kind: ChannelPolicyKind get() = id.kind

    override fun equals(other: Any?): Boolean =
        this === other || (other is ChannelPolicy && id == other.id)

    override fun hashCode(): Int = javaClass.hashCode()
}
