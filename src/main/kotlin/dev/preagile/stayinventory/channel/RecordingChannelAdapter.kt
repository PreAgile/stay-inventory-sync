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
        forcedResult = null
    }

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
        return ChannelSyncResult.Success(deduplicated = false)
    }
}
