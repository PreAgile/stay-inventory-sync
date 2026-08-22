package dev.preagile.stayinventory.channel

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 실제 채널 대신 **호출을 기록하는** 어댑터.
 *
 * 이 저장소의 범위는 정합성 증명이지 채널 연동이 아니다(`docs/02-scope.md`).
 * 그래서 스텁을 두되, **아무것도 안 하는 스텁으로 두지 않는다** -- 멱등키로
 * 중복을 흡수하는 동작이 있어야 `T4` 가 증명하려는 것을 증명할 수 있다.
 *
 * 실제 채널이 하는 일을 흉내 낸다.
 *
 * | 이 스텁 | 실제 채널 |
 * |---|---|
 * | 멱등키를 본 적 있으면 `Success(deduplicated = true)` | 같은 요청 ID 를 같은 사건으로 흡수 |
 * | `applied` 는 그때 증가하지 않는다 | 실질 부작용이 한 번만 일어난다 |
 *
 * **`attempts` 와 `applied` 를 나눠 세는 것이 핵심이다.** at-least-once 는
 * 호출이 여러 번 나가는 것을 막지 않는다. 막는 것은 **부작용이 여러 번 나는 것**이고,
 * 두 숫자를 하나로 세면 그 구분이 사라진다.
 */
@Component
class RecordingChannelAdapter : ChannelAdapter {

    private val log = LoggerFactory.getLogger(javaClass)

    override val channel: String = "RECORDING"

    private val seenKeys = ConcurrentHashMap.newKeySet<Long>()
    private val attemptCounter = AtomicInteger()
    private val appliedCounter = AtomicInteger()
    private val lastPayloadByKey = ConcurrentHashMap<Long, String>()

    /** 다음 호출부터 이 결과를 돌려준다. null 이면 정상 동작. 테스트가 쓴다. */
    @Volatile
    var forcedResult: ChannelSyncResult? = null

    val attempts: Int get() = attemptCounter.get()

    /** 실질 부작용 횟수. `T4` 가 이 숫자를 본다. */
    val applied: Int get() = appliedCounter.get()

    fun payloadOf(idempotencyKey: Long): String? = lastPayloadByKey[idempotencyKey]

    fun reset() {
        seenKeys.clear()
        attemptCounter.set(0)
        appliedCounter.set(0)
        lastPayloadByKey.clear()
        channelState.clear()
        silentlyDropped.clear()
        forcedResult = null
    }

    /**
     * payload 를 읽어 채널 쪽 상태를 갱신한다.
     *
     * 문자열을 정규식으로 판다. 어댑터에 Jackson 을 들이면 payload 의 **구조**를
     * 어댑터가 알게 되고, 그 순간 "발행 시점에 다시 계산" 하는 경로가 열린다.
     * 스텁이 하는 일은 값을 기억하는 것뿐이다.
     */
    private fun applyToChannelState(payload: String) {
        val roomTypeId = FIELD.find(payload, "roomTypeId")?.toLongOrNull() ?: return
        val stayDate = QUOTED.find(payload, "stayDate")?.let(java.time.LocalDate::parse) ?: return
        val remaining = FIELD.find(payload, "remaining")?.toIntOrNull() ?: return

        val key = roomTypeId to stayDate
        // 조용한 누락. 응답은 Success 인데 값만 안 바뀐다
        if (key in silentlyDropped) return
        channelState[key] = remaining
    }

    private companion object {
        val FIELD = FieldReader("""\"%s\"\s*:\s*(-?\d+)""")
        val QUOTED = FieldReader("""\"%s\"\s*:\s*\"([^\"]+)\"""")
    }

    private class FieldReader(private val template: String) {
        fun find(payload: String, field: String): String? =
            Regex(template.format(field)).find(payload)?.groupValues?.get(1)
    }

    /**
     * 채널이 들고 있다고 **주장하는** 재고. 이 스텁에서는 실제로 반영된 통보의
     * 결과다.
     *
     * [dropSilently] 로 특정 날짜의 반영을 **조용히 누락**시킬 수 있다.
     * 실패도 예외도 없이 값만 안 바뀌는 것이 실제 연동에서 가장 흔한 어긋남이고,
     * `#6` 이 잡겠다고 선언한 것이 정확히 그 상황이다.
     */
    private val channelState = ConcurrentHashMap<Pair<Long, java.time.LocalDate>, Int>()

    /** 이 (룸타입, 날짜)에 대한 반영을 조용히 누락한다. 응답은 여전히 `Success` 다. */
    private val silentlyDropped = ConcurrentHashMap.newKeySet<Pair<Long, java.time.LocalDate>>()

    fun dropSilently(roomTypeId: Long, stayDate: java.time.LocalDate) {
        silentlyDropped.add(roomTypeId to stayDate)
    }

    override fun currentInventory(
        roomTypeId: Long,
        from: java.time.LocalDate,
        to: java.time.LocalDate,
    ): Map<java.time.LocalDate, Int> = channelState
        .filterKeys { (rt, date) -> rt == roomTypeId && date >= from && date < to }
        .mapKeys { (key, _) -> key.second }

    override fun push(idempotencyKey: Long, payload: String): ChannelSyncResult {
        attemptCounter.incrementAndGet()

        forcedResult?.let { return it }

        // add 가 false 면 이미 본 키다. 채널이 멱등키로 흡수하는 동작이 이것이다.
        val firstTime = seenKeys.add(idempotencyKey)
        if (!firstTime) {
            log.debug("멱등키 {} 재수신 — 흡수", idempotencyKey)
            return ChannelSyncResult.Success(deduplicated = true)
        }

        appliedCounter.incrementAndGet()
        lastPayloadByKey[idempotencyKey] = payload
        applyToChannelState(payload)
        return ChannelSyncResult.Success(deduplicated = false)
    }
}
