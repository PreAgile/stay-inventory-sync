package dev.preagile.stayinventory.ops

import dev.preagile.stayinventory.resync.InventoryResyncScheduler
import dev.preagile.stayinventory.resync.InventoryResyncService
import dev.preagile.stayinventory.resync.ResyncReport
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * 재동기화 수동 트리거 (`#67`).
 *
 * 주기가 하루이므로 사고 직후 즉시 수렴시킬 경로가 없었다. 채널과 숫자가 어긋난
 * 것을 diff 로 확인한 운영자가 **다음 주기를 기다리지 않고** 돌릴 수 있어야 한다.
 *
 * 인증·권한은 범위 밖이다(`docs/02-scope.md`). 붙을 위치는 필터 체인이다.
 *
 * ## 동시 클릭은 임대가 막는다
 *
 * 두 운영자가 같이 누르거나 스케줄러와 겹치면 **뒤에 온 쪽이 `409`** 를 받는다.
 * 조용히 성공을 돌려주면 운영자는 두 번 돌았다고 믿고, 실제로는 한 번 돌았거나
 * 둘 다 절반씩 돈다 — 어느 쪽인지 알 수 없는 상태가 가장 나쁘다.
 */
@RestController
@RequestMapping("/ops/resync")
class ResyncOpsController(
    private val resync: InventoryResyncService,
) {

    /**
     * 한 주기를 지금 돈다. **한 바퀴가 아니라 한 주기다** — 상한만큼만 보내고
     * 커서를 옮긴다. 전 구간을 덮으려면 `cycleCompleted` 가 참이 될 때까지
     * 반복해야 하고, 그 사실을 응답이 그대로 말한다.
     */
    @PostMapping
    fun trigger(
        @RequestParam(required = false) from: LocalDate?,
        @RequestParam(required = false) to: LocalDate?,
    ): ResponseEntity<Any> {
        val start = from ?: LocalDate.now()
        val end = to ?: start.plusDays(InventoryResyncScheduler.HORIZON_DAYS)

        val report = resync.resync(start, end)
        return if (report.skipped) {
            // 임대를 못 잡았다. 스케줄러가 돌고 있거나 다른 운영자가 눌렀다.
            ResponseEntity.status(HttpStatus.CONFLICT).body(SkippedResponse())
        } else {
            ResponseEntity.ok(report)
        }
    }
}

/** `409` 의 본문. 무엇을 해야 하는지까지 적는다 — 상태코드만으로는 알 수 없다. */
data class SkippedResponse(
    val status: String = "SKIPPED",
    val reason: String = "다른 인스턴스나 스케줄러가 재동기화를 돌고 있다. 끝난 뒤 다시 누른다",
)
