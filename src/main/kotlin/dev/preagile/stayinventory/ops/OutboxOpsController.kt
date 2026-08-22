package dev.preagile.stayinventory.ops

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/**
 * 영구 실패한 통보의 종착지와 수동 재투입.
 *
 * **자동 재처리를 넣지 않았다.** `DEAD` 는 "재시도해도 결과가 같다" 는 판정이고,
 * 자동으로 되살리면 그 판정을 스스로 부정하는 것이다. 원인을 모르는 상태의
 * 자동 복구는 같은 실패를 더 빠르게 반복할 뿐이다.
 *
 * 인증·권한은 범위 밖이다(`docs/02-scope.md`). 운영 도구가 무엇을 보여 주고
 * 무엇을 할 수 있어야 하는지를 정하는 것이 이 슬라이스의 관심사다.
 */
@RestController
@RequestMapping("/ops/outbox")
class OutboxOpsController(
    private val jdbc: JdbcTemplate,
) {

    /**
     * 영구 실패 목록.
     *
     * `payload` 를 함께 준다. **무엇이 안 나갔는지 모르면 되살릴지 판단할 수 없다** --
     * 재고 통보라면 지금 값과 비교해야 하고, 이미 더 새로운 값이 나갔다면
     * 되살릴 이유가 없다.
     */
    @GetMapping("/dead")
    fun dead(@RequestParam(defaultValue = "100") limit: Int): List<DeadEvent> =
        jdbc.query(
            """
            SELECT id, aggregate_type, aggregate_id, event_type, payload::text,
                   retry_count, room_type_id, stay_date, version, created_at
              FROM outbox_event
             WHERE status = 'DEAD'
             ORDER BY id
             LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                DeadEvent(
                    id = rs.getLong(1),
                    aggregateType = rs.getString(2),
                    aggregateId = rs.getLong(3),
                    eventType = rs.getString(4),
                    payload = rs.getString(5),
                    retryCount = rs.getInt(6),
                    roomTypeId = rs.getObject(7) as Long?,
                    stayDate = rs.getObject(8, java.time.LocalDate::class.java),
                    version = rs.getObject(9) as Long?,
                    createdAt = rs.getTimestamp(10).toInstant(),
                )
            },
            limit,
        )

    /**
     * 수동 재투입.
     *
     * `retry_count` 를 0 으로 되돌린다. 되돌리지 않으면 다음 실패 한 번에 다시
     * `DEAD` 가 되어 재투입이 사실상 아무 일도 하지 않는다 -- 사람이 원인을
     * 고쳤다고 판단해서 누른 버튼이므로 예산을 새로 준다.
     *
     * **`DEAD` 인 것만 되살린다.** `PENDING` 을 되살리면 백오프가 초기화되어
     * 장애 중인 채널을 더 세게 때리고, `PUBLISHED` 를 되살리면 이미 나간 통보가
     * 다시 나간다 -- 그것도 **낡은 값으로** 나간다.
     */
    @PostMapping("/{id}/retry")
    fun retry(@PathVariable id: Long): ResponseEntity<Any> {
        val moved = jdbc.update(
            """
            UPDATE outbox_event
               SET status = 'PENDING', retry_count = 0, next_attempt_at = ?
             WHERE id = ? AND status = 'DEAD'
            """.trimIndent(),
            java.sql.Timestamp.from(Instant.now()),
            id,
        )

        // 조건부 UPDATE 의 rowcount 로 판정한다. 상태를 읽고 비교하면 읽기와
        // 쓰기 사이가 열리고, 두 운영자가 동시에 누르면 둘 다 통과한다.
        return if (moved == 1) {
            ResponseEntity.ok(mapOf("status" to "REQUEUED", "id" to id))
        } else {
            ResponseEntity.status(HttpStatus.CONFLICT)
                .body(mapOf("message" to "$id 는 DEAD 상태가 아니다"))
        }
    }
}

data class DeadEvent(
    val id: Long,
    val aggregateType: String,
    val aggregateId: Long,
    val eventType: String,
    val payload: String,
    val retryCount: Int,
    val roomTypeId: Long?,
    val stayDate: java.time.LocalDate?,
    val version: Long?,
    val createdAt: Instant,
)
