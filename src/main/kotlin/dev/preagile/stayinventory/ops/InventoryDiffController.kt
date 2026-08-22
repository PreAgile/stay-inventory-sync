package dev.preagile.stayinventory.ops

import dev.preagile.stayinventory.channel.ChannelAdapter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * 내부 재고와 채널이 들고 있다고 말하는 값을 **대조**한다.
 *
 * ## 왜 Tier 1 인가 — 순서가 이 항목의 전부다
 *
 * Tier 0 보다 먼저 만들면 *"눈으로 하던 대조를 화면으로 하게 됐다"* 에서 끝난다.
 * **확인 업무의 형태만 바뀌고 총량은 그대로다.**
 *
 * 정합성이 구조적으로 보장된 뒤의 이 리포트는 성격이 다르다 — 일상 업무가 아니라
 * **예외 감지 장치**다. 평소에는 비어 있어야 정상이고, 뭔가 뜨면 진짜 이상이다.
 *
 * ## 자동 보정을 하지 않는다
 *
 * 원인을 모르는 상태의 자동 보정은 위험하다. 어긋남의 원인은 셋인데
 * (`docs/07-reconciliation.md`) **대응이 각각 다르다.**
 *
 * - 통보가 안 나갔다 → 재발행
 * - 채널에서 사람이 바꿨다 → 정책 축 판정 (`ADR-0009`)
 * - 우리 계산이 틀렸다 → **재발행하면 틀린 값을 더 확실히 밀어넣는다**
 *
 * 셋을 구분하지 못하는 자동 보정은 세 번째 경우에 상황을 악화시킨다.
 */
@RestController
@RequestMapping("/ops/inventory-diff")
class InventoryDiffController(
    private val jdbc: JdbcTemplate,
    private val adapters: List<ChannelAdapter>,
) {

    @GetMapping
    fun diff(
        @RequestParam propertyId: Long,
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate,
    ): List<InventoryDiff> {
        require(from < to) { "from 은 to 보다 앞이어야 한다: $from ~ $to" }

        val internal = internalRemaining(propertyId, from, to)

        return adapters.flatMap { adapter ->
            val roomTypeIds = internal.keys.map { it.first }.distinct()
            val channelByRoomType = roomTypeIds.associateWith { roomTypeId ->
                adapter.currentInventory(roomTypeId, from, to)
            }

            internal.mapNotNull { (key, internalRemaining) ->
                val (roomTypeId, stayDate) = key
                val channelRemaining = channelByRoomType[roomTypeId]?.get(stayDate)

                // 값이 같으면 리포트에 넣지 않는다. **평소에 비어 있어야
                // 뭔가 떴을 때 그것이 신호로 읽힌다** -- 전부 나열하면
                // 사람이 다시 눈으로 훑게 되고, 그건 확인 업무의 부활이다
                if (channelRemaining == internalRemaining) {
                    null
                } else {
                    InventoryDiff(
                        channel = adapter.channel,
                        roomTypeId = roomTypeId,
                        stayDate = stayDate,
                        internal = internalRemaining,
                        // null 은 0 이 아니다. 0 은 "매진이라고 안다" 이고
                        // null 은 "그 날짜를 아예 모른다" 이며, 후자는 통보가
                        // 도달한 적이 없다는 뜻이다
                        channelValue = channelRemaining,
                        delta = channelRemaining?.let { it - internalRemaining },
                    )
                }
            }
        }.sortedWith(compareBy({ it.channel }, { it.roomTypeId }, { it.stayDate }))
    }

    /** `(룸타입, 날짜) -> 잔여`. 잔여는 계산값이므로 여기서 만든다. */
    private fun internalRemaining(
        propertyId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Map<Pair<Long, LocalDate>, Int> =
        jdbc.query(
            """
            SELECT di.room_type_id, di.stay_date,
                   di.physical_total + di.overbooking_limit - di.sold
              FROM daily_inventory di
              JOIN room_type rt ON rt.id = di.room_type_id
             WHERE rt.property_id = ? AND di.stay_date >= ? AND di.stay_date < ?
             ORDER BY di.room_type_id, di.stay_date
            """.trimIndent(),
            { rs, _ ->
                (rs.getLong(1) to rs.getObject(2, LocalDate::class.java)) to rs.getInt(3)
            },
            propertyId,
            from,
            to,
        ).toMap()
}

/**
 * 한 건의 어긋남.
 *
 * [channelValue] 가 null 이면 채널이 그 날짜를 모른다는 뜻이고, 그때 [delta] 도
 * null 이다. **0 으로 채우지 않는다** -- "매진이라고 안다" 와 "모른다" 는
 * 원인도 대응도 다르다.
 */
data class InventoryDiff(
    val channel: String,
    val roomTypeId: Long,
    val stayDate: LocalDate,
    val internal: Int,
    val channelValue: Int?,
    val delta: Int?,
)
