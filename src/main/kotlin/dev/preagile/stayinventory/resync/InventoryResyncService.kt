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
 * ## 이 서비스는 도메인을 쓰지 않는다
 *
 * 재고를 만지지 않으므로 `INV-1` ~ `INV-4` 와 무관하고, 실패해도 내부 상태가
 * 어긋나지 않는다 — **최종 방어선이 스스로 정합성을 깰 수 없다는 것이 이 설계의
 * 요건이다.** `resync_cursor` 만 쓴다. 그것은 진행 상태이지 도메인이 아니다.
 *
 * ## 한 바퀴로 전 구간을 덮는다 (`#71`)
 *
 * 처음 판은 매 주기 **같은 앞 500건**을 읽었다. 커서가 없어 격자가 상한을 넘으면
 * 501번째부터는 영원히 선택되지 않았다 — **최종 방어선이 부분만 덮고 있었다.**
 *
 * 키셋 커서로 고쳤다. `(room_type_id, stay_date)` 순서로 훑고 **이전 주기가 멈춘
 * 지점 뒤부터** 읽는다. 구간 끝에 닿으면 커서를 처음으로 되돌린다.
 * 격자가 N 이면 **`ceil(N / limit)` 주기에 한 바퀴**가 돈다.
 *
 * `OFFSET` 을 쓰지 않는 이유는 격자가 자라기 때문이다. 앞에 행이 끼어들면
 * `OFFSET` 은 이미 보낸 것을 다시 보내거나 **안 보낸 것을 건너뛴다.**
 *
 * ## 인스턴스 하나만 돈다 (`#67`)
 *
 * 임대를 잡지 못하면 그 주기를 건너뛴다. 방어가 없던 판에서는 인스턴스 N대면
 * 전량이 N번 나갔고, **스냅샷은 멱등키를 일부러 받지 않으므로 채널도 흡수하지
 * 못했다** — 최종 방어선이 동시에 가장 큰 부하원이었다.
 *
 * 임대 방식은 릴레이와 같다. 트랜잭션 락으로 잡으면 외부 호출 동안 커넥션을
 * 붙잡으므로, **조건부 UPDATE 로 미래 시각을 심고 곧 커밋한다.**
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

        // 임대를 못 잡으면 다른 인스턴스가 돌고 있다. 조용히 건너뛰지 않고
        // 보고에 남긴다 -- "돌지 않았다" 와 "돌았는데 보낼 것이 없었다" 는 다르다.
        if (!acquireLease()) {
            log.debug("재동기화 임대를 다른 인스턴스가 들고 있다. 이 주기를 건너뛴다")
            return ResyncReport(skipped = true)
        }

        return try {
            runCycle(adapter, from, to, limit)
        } finally {
            // 실패해도 임대를 놓는다. 놓지 않으면 다음 주기까지 아무도 못 돈다.
            // 프로세스가 죽는 경우는 leased_until 이 지나며 스스로 풀린다.
            releaseLease()
        }
    }

    private fun runCycle(
        adapter: ChannelAdapter,
        from: LocalDate,
        to: LocalDate,
        limit: Int,
    ): ResyncReport {
        val cursor = readCursor()
        val snapshots = currentInventory(from, to, cursor, limit + 1)
        val hasMore = snapshots.size > limit
        val batch = snapshots.take(limit)

        var sent = 0
        var failed = 0
        batch.forEach { snapshot ->
            val result = runCatching {
                adapter.pushSnapshot(snapshot.roomTypeId, snapshot.stayDate, snapshot.remaining)
            }.getOrElse { ChannelSyncResult.Retryable("어댑터 예외: ${it.message}") }

            when (result) {
                is ChannelSyncResult.Success -> sent++
                // 재시도하지 않는다. 다음 바퀴가 어차피 같은 값을 다시 보낸다 --
                // 여기서 재시도 큐를 만들면 재동기화가 또 하나의 발행 경로가 되고,
                // 그 경로에 순서 문제가 생긴다. 최종 방어선은 단순해야 한다.
                else -> failed++
            }
        }

        // 커서를 옮긴다. 보낸 것의 끝을 기록하므로 **전송 실패도 진행으로 센다** --
        // 실패한 건에서 멈추면 그 건이 영구히 막아 뒤쪽이 다시 굶는다(#71 의 형태).
        // 실패는 다음 바퀴가 다시 시도한다.
        val advanced = if (hasMore) batch.lastOrNull() else null
        writeCursor(advanced)

        if (!hasMore) {
            log.info("재동기화가 구간을 한 바퀴 돌았다. 커서를 처음으로 되돌린다")
        }
        return ResyncReport(
            sent = sent,
            failed = failed,
            hasMore = hasMore,
            cycleCompleted = !hasMore,
        )
    }

    /**
     * 조건부 UPDATE 로 임대를 잡는다. `rowcount` 가 판정이다 --
     * 읽고 나서 쓰면 두 인스턴스가 모두 "비어 있다" 를 보고 둘 다 심는다.
     */
    private fun acquireLease(): Boolean =
        jdbc.update(
            """
            UPDATE resync_cursor
               SET leased_until = now() + ?::interval, updated_at = now()
             WHERE id = 1
               AND (leased_until IS NULL OR leased_until <= now())
            """.trimIndent(),
            "$LEASE_SECONDS seconds",
        ) == 1

    private fun releaseLease() {
        jdbc.update("UPDATE resync_cursor SET leased_until = NULL, updated_at = now() WHERE id = 1")
    }

    private fun readCursor(): Cursor =
        jdbc.queryForObject(
            "SELECT last_room_type_id, last_stay_date FROM resync_cursor WHERE id = 1",
        ) { rs, _ -> Cursor(rs.getLong(1), rs.getObject(2, LocalDate::class.java)) }
            ?: Cursor(0, LocalDate.of(1, 1, 1))

    /** [advanced] 가 null 이면 한 바퀴가 끝났다는 뜻이므로 처음으로 되돌린다. */
    private fun writeCursor(advanced: Snapshot?) {
        jdbc.update(
            """
            UPDATE resync_cursor
               SET last_room_type_id = ?, last_stay_date = ?, updated_at = now()
             WHERE id = 1
            """.trimIndent(),
            advanced?.roomTypeId ?: 0L,
            advanced?.stayDate ?: LocalDate.of(1, 1, 1),
        )
    }

    private fun currentInventory(
        from: LocalDate,
        to: LocalDate,
        cursor: Cursor,
        limit: Int,
    ): List<Snapshot> =
        jdbc.query(
            """
            SELECT room_type_id, stay_date, physical_total + overbooking_limit - sold
              FROM daily_inventory
             WHERE stay_date >= ? AND stay_date < ?
               AND (room_type_id, stay_date) > (?, ?)
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
            cursor.roomTypeId,
            cursor.stayDate,
            limit,
        )

    private data class Cursor(val roomTypeId: Long, val stayDate: LocalDate)

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

        /**
         * 임대 길이.
         *
         * 한 주기가 `DEFAULT_LIMIT` 건을 외부로 보내는 시간보다 넉넉해야 한다.
         * 짧으면 아직 도는 인스턴스의 임대가 풀려 두 대가 겹치고, 길면 죽은
         * 인스턴스가 남긴 임대 때문에 그만큼 재동기화가 멈춘다.
         *
         * 릴레이의 임대(1분)보다 긴 이유는 한 주기의 전송량이 훨씬 크기 때문이다.
         */
        const val LEASE_SECONDS = 600
    }
}

/**
 * 한 주기의 결과.
 *
 * [hasMore] 가 참이면 구간에 아직 남은 격자가 있다는 뜻이고, 다음 주기가 그 뒤부터
 * 이어 간다. **이 값을 보고에서 빼면 "재동기화했다" 는 신호가 거짓이 된다** —
 * 일부만 보내고도 성공으로 읽힌다.
 *
 * [cycleCompleted] 는 한 바퀴가 끝났다는 신호다. 두 값을 나눠 두는 이유는
 * **"진행 중" 과 "한 바퀴 완료" 가 운영자에게 다른 의미**이기 때문이다 —
 * 후자가 나올 때까지 전 구간이 덮였다고 말할 수 없다.
 *
 * [skipped] 는 임대를 못 잡아 돌지 않았다는 뜻이다. **"돌지 않았다" 와
 * "돌았는데 보낼 것이 없었다" 를 구분하지 않으면 재동기화가 멈춘 것을 못 본다.**
 */
data class ResyncReport(
    val sent: Int = 0,
    val failed: Int = 0,
    val hasMore: Boolean = false,
    val cycleCompleted: Boolean = false,
    val skipped: Boolean = false,
)
