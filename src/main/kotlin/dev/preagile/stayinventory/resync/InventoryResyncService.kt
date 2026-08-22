package dev.preagile.stayinventory.resync

import dev.preagile.stayinventory.channel.ChannelAdapter
import dev.preagile.stayinventory.channel.ChannelSyncResult
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.time.LocalDate

/**
 * 키별 현재 재고를 **주기적으로 전량 재전송**해 채널 상태를 내부 진실로 수렴시킨다.
 *
 * ## 왜 필요한가 — `B4` 가 닫지 못하는 창
 *
 * `ADR-0012` 의 버전 스탬프는 `PUBLISHED` 마킹을 기준으로 낡음을 판별한다.
 * 그런데 **마킹은 외부 호출이 성공한 뒤에** 일어난다.
 *
 * ```
 * 외부 호출 성공 ──▶ (릴레이 종료) ──▶ PUBLISHED 마킹 없음
 *                                       낡은 재시도가 skip 검사를 통과한다
 * ```
 *
 * `T4` 가 다루는 창과 **같은 창인데 결과의 성격이 다르다.**
 *
 * | 창 안에서 | |
 * |---|---|
 * | 중복 발행 | 멱등하게 흡수된다 — 안전 |
 * | **낡은 값 재전송** | **흡수되지 않는다** — 최신 값을 덮어쓴다 |
 *
 * 닫는 방법 두 가지가 모두 불완전하다(`ADR-0012` 기각 대안 2·3). 그래서 창을 0 으로
 * 만드는 대신 **틀린 상태가 남더라도 유한 시간 안에 스스로 고쳐지게** 만든다.
 * **순서 문제의 최종 방어선은 순서가 아니라 재동기화다.**
 *
 * ## 이벤트 큐를 경유하지 않는다
 *
 * Outbox 를 타면 이 통보도 버전 판정을 받는데, **재동기화의 값은 언제나 최신이므로
 * 판정 대상이 아니다.** 그리고 큐를 타면 밀려 있던 낡은 이벤트 뒤에 줄을 서게 되어
 * 정작 고치려던 상태를 늦게 고친다.
 *
 * ## 이 서비스는 아무것도 쓰지 않는다
 *
 * 읽고 보낸다. 재고를 만지지 않으므로 `INV-1` ~ `INV-4` 와 무관하고,
 * 실패해도 내부 상태가 어긋나지 않는다 — **최종 방어선이 스스로 정합성을 깰 수
 * 없다는 것이 이 설계의 요건이다.**
 */
@Service
class InventoryResyncService(
    private val jdbc: JdbcTemplate,
    private val adapters: List<ChannelAdapter>,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * `[from, to)` 구간의 격자를 전부 재전송한다.
     *
     * @param limit 한 번에 보낼 최대 건수. 레이트 리밋이 분당 20건인데
     *   격자가 수천 개일 수 있으므로 상한이 필요하다. **잘렸다는 사실을
     *   보고에 담는다** — 조용히 자르면 "재동기화했다" 는 신호가 거짓이 된다.
     */
    fun resync(
        from: LocalDate,
        to: LocalDate,
        limit: Int = DEFAULT_LIMIT,
    ): ResyncReport {
        require(from < to) { "from 은 to 보다 앞이어야 한다: $from ~ $to" }
        val adapter = adapters.firstOrNull() ?: return ResyncReport()

        val snapshots = currentInventory(from, to, limit + 1)
        val truncated = snapshots.size > limit
        val batch = snapshots.take(limit)

        var sent = 0
        var failed = 0
        batch.forEach { snapshot ->
            val result = runCatching {
                adapter.pushSnapshot(snapshot.roomTypeId, snapshot.stayDate, snapshot.remaining)
            }.getOrElse { ChannelSyncResult.Retryable("어댑터 예외: ${it.message}") }

            when (result) {
                is ChannelSyncResult.Success -> sent++
                // 재시도하지 않는다. 다음 주기가 어차피 같은 값을 다시 보낸다 --
                // 여기서 재시도 큐를 만들면 재동기화가 또 하나의 발행 경로가 되고,
                // 그 경로에 순서 문제가 생긴다. 최종 방어선은 단순해야 한다.
                else -> failed++
            }
        }

        if (truncated) {
            log.warn("재동기화가 상한 {}건에서 잘렸다. 남은 구간은 다음 주기가 처리한다", limit)
        }
        return ResyncReport(sent = sent, failed = failed, truncated = truncated)
    }

    private fun currentInventory(from: LocalDate, to: LocalDate, limit: Int): List<Snapshot> =
        jdbc.query(
            """
            SELECT room_type_id, stay_date, physical_total + overbooking_limit - sold
              FROM daily_inventory
             WHERE stay_date >= ? AND stay_date < ?
             ORDER BY room_type_id, stay_date
             LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                Snapshot(
                    roomTypeId = rs.getLong(1),
                    stayDate = rs.getObject(2, LocalDate::class.java),
                    remaining = rs.getInt(3),
                )
            },
            from,
            to,
            limit,
        )

    private data class Snapshot(
        val roomTypeId: Long,
        val stayDate: LocalDate,
        val remaining: Int,
    )

    companion object {
        /**
         * 한 주기에 보낼 상한.
         *
         * 레이트 리밋이 분당 20건이므로 이 값은 **주기와 함께 읽어야** 의미가 있다.
         * 지금은 하루 한 번 도는 것을 상정하고, 실제 격자 크기가 확인되면
         * `04-capacity-and-limits.md` 의 산정에 맞춰 다시 잡는다.
         */
        const val DEFAULT_LIMIT = 500
    }
}

/**
 * [truncated] 가 참이면 구간의 일부만 보냈다는 뜻이다.
 *
 * **이 값을 보고에서 빼면 "재동기화했다" 는 신호가 거짓이 된다** — 절반만
 * 보내고도 성공으로 읽힌다.
 */
data class ResyncReport(
    val sent: Int = 0,
    val failed: Int = 0,
    val truncated: Boolean = false,
)
