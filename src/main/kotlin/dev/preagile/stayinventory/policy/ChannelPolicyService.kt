package dev.preagile.stayinventory.policy

import com.fasterxml.jackson.databind.ObjectMapper
import dev.preagile.stayinventory.domain.ChannelPolicyKind
import dev.preagile.stayinventory.domain.ChannelPolicySource
import dev.preagile.stayinventory.persistence.OutboxEvent
import dev.preagile.stayinventory.persistence.OutboxEventRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

/**
 * 채널별 노출 상한(캡)을 관리한다. `ADR-0001` 이 채택한 **캡형 hybrid** 다.
 *
 * ## 재고 모델을 바꾸지 않는다
 *
 * ```
 * 우리 DB     10                     카운터 하나. pooled 그대로
 * 채널 A · B  10                     규칙 없음
 * 채널 C       5                     max_availability = 5
 * ```
 *
 * **C 가 5 를 확보한 것이 아니다.** 실제 판매 가능량은 `min(캡, 잔여)` 이고,
 * 그 `min` 을 계산하는 것은 우리가 아니라 **채널**이다 -- 우리는 규칙만 보낸다.
 * 그래서 남은 방이 있는데 매진이 뜨는 상황이 만들어지지 않는다.
 *
 * `INV-1` ~ `INV-4` 가 하나도 늘지 않고 `daily_inventory` 스키마도 손대지 않는다.
 * **바뀌면 캡형이 아니라 배정을 구현한 것이다.**
 *
 * ## 왜 균등 노출이 요구사항이 아닌가
 *
 * 채널마다 수수료가 다르고 취소율이 다르다. *"모든 채널에 똑같이 다 노출한다"*
 * 만 되는 시스템은 실무에서 거부당하고, 거부당하면 현장이 채널 화면에서 직접
 * 조정한다. **그 순간 정책 축의 원본이 우리 장부가 아니라 채널이 된다** (ADR-0009).
 *
 * ## 버전 단조성은 어디서 오는가
 *
 * 재고 통보가 `daily_inventory` 행 락에 기대는 것처럼, 정책 통보는
 * **`channel_policy` 행 락**에 기댄다. `INSERT ... ON CONFLICT` 가 그 행을 잠그므로
 * 같은 키의 두 변경이 직렬화된다. **그래서 upsert 가 버전 계산보다 먼저다** --
 * 순서를 뒤집으면 두 트랜잭션이 같은 번호를 받아 간다.
 */
@Service
class ChannelPolicyService(
    private val jdbc: JdbcTemplate,
    private val outbox: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
) {

    /**
     * 캡을 걸거나 값을 바꾼다.
     *
     * `source` 는 언제나 `OURS` 다. `CHANNEL` 은 인바운드 흡수 경로가 쓰는 값이고
     * 이 API 가 건드리지 않는다 -- 섞으면 "이 값을 우리가 정했나 현장이 정했나" 를
     * 나중에 물을 수 없게 된다.
     */
    @Transactional
    fun setCap(roomTypeId: Long, stayDate: LocalDate, channel: String, value: Int) {
        require(value >= 0) { "캡은 0 이상이어야 한다: $value" }

        val now = Instant.now()

        // ① upsert 가 먼저다. 이 문장이 잡는 행 락이 아래 버전 계산을 직렬화한다.
        jdbc.update(
            """
            INSERT INTO channel_policy (room_type_id, stay_date, channel, kind, value, source, updated_at)
            VALUES (?, ?, ?, 'CAP', ?, 'OURS', ?)
            ON CONFLICT (room_type_id, stay_date, channel, kind)
            DO UPDATE SET value = EXCLUDED.value, source = 'OURS', updated_at = EXCLUDED.updated_at
            """.trimIndent(),
            roomTypeId,
            stayDate,
            channel,
            value,
            java.sql.Timestamp.from(now),
        )

        // ② 통보를 같은 트랜잭션에 적는다 (절대 규칙 3).
        //    장부만 바뀌고 채널에 안 나가면 "우리는 5 로 알고 채널은 10 을 보여 주는"
        //    상태가 되고, 그 차이는 diff 리포트(#6)에도 안 잡힌다 -- 그쪽은 재고를 본다.
        appendPolicyEvent(roomTypeId, stayDate, channel, value, removed = false, now = now)
    }

    /** 캡을 해제한다. **재고는 건드리지 않는다** -- 애초에 건드린 적이 없다. */
    @Transactional
    fun removeCap(roomTypeId: Long, stayDate: LocalDate, channel: String): Boolean {
        val now = Instant.now()
        val deleted = jdbc.update(
            """
            DELETE FROM channel_policy
             WHERE room_type_id = ? AND stay_date = ? AND channel = ? AND kind = 'CAP'
            """.trimIndent(),
            roomTypeId,
            stayDate,
            channel,
        )

        // 없던 것을 지우는 요청에는 통보를 만들지 않는다. 만들면 채널이
        // "해제하라" 를 반복해서 받고, 레이트 리밋만 태운다.
        if (deleted == 0) return false

        appendPolicyEvent(roomTypeId, stayDate, channel, value = null, removed = true, now = now)
        return true
    }

    @Transactional(readOnly = true)
    fun list(propertyId: Long, from: LocalDate, to: LocalDate): List<ChannelPolicyView> {
        require(from < to) { "from 은 to 보다 앞이어야 한다: $from ~ $to" }
        return jdbc.query(
            """
            SELECT cp.room_type_id, cp.stay_date, cp.channel, cp.kind, cp.value, cp.source
              FROM channel_policy cp
              JOIN room_type rt ON rt.id = cp.room_type_id
             WHERE rt.property_id = ? AND cp.stay_date >= ? AND cp.stay_date < ?
             ORDER BY cp.room_type_id, cp.stay_date, cp.channel, cp.kind
            """.trimIndent(),
            { rs, _ ->
                ChannelPolicyView(
                    roomTypeId = rs.getLong(1),
                    stayDate = rs.getObject(2, LocalDate::class.java),
                    channel = rs.getString(3),
                    kind = ChannelPolicyKind.valueOf(rs.getString(4)),
                    value = rs.getObject(5) as Int?,
                    source = ChannelPolicySource.valueOf(rs.getString(6)),
                )
            },
            propertyId,
            from,
            to,
        )
    }

    private fun appendPolicyEvent(
        roomTypeId: Long,
        stayDate: LocalDate,
        channel: String,
        value: Int?,
        removed: Boolean,
        now: Instant,
    ) {
        outbox.save(
            OutboxEvent(
                // 재고 통보와 **다른 레인**이다. 같은 격자를 쓰지만 서로를 낡게
                // 만들면 안 된다 -- 캡을 바꿨다고 재고 통보가 건너뛰어지면
                // 채널은 잔여를 영영 모른다.
                aggregateType = AGGREGATE_CHANNEL_POLICY,
                aggregateId = roomTypeId,
                roomTypeId = roomTypeId,
                stayDate = stayDate,
                version = outbox.nextVersionFor(AGGREGATE_CHANNEL_POLICY, roomTypeId, stayDate),
                eventType = "CHANNEL_POLICY_CHANGED",
                payload = objectMapper.writeValueAsString(
                    ChannelPolicyChangedPayload(
                        roomTypeId = roomTypeId,
                        stayDate = stayDate,
                        channel = channel,
                        kind = ChannelPolicyKind.CAP,
                        value = value,
                        removed = removed,
                    ),
                ),
                createdAt = now,
                nextAttemptAt = now,
            ),
        )
    }

    companion object {
        /** 정책 통보의 레인. 재고와 버전을 공유하지 않는다. */
        const val AGGREGATE_CHANNEL_POLICY = "CHANNEL_POLICY"
    }
}

/**
 * 채널로 나갈 규칙 변경.
 *
 * [removed] 를 두는 이유는 `value = null` 만으로는 **"상한 없음"** 과
 * **"이 통보에 값이 안 실렸다"** 를 구분할 수 없기 때문이다.
 */
data class ChannelPolicyChangedPayload(
    val roomTypeId: Long,
    val stayDate: LocalDate,
    val channel: String,
    val kind: ChannelPolicyKind,
    val value: Int?,
    val removed: Boolean,
)

data class ChannelPolicyView(
    val roomTypeId: Long,
    val stayDate: LocalDate,
    val channel: String,
    val kind: ChannelPolicyKind,
    val value: Int?,
    val source: ChannelPolicySource,
)
