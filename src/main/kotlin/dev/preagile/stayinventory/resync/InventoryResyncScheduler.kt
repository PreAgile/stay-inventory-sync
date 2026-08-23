package dev.preagile.stayinventory.resync

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 재동기화를 주기적으로 돌린다.
 *
 * **주기가 곧 이 방어선의 성능이다.** 창이 열려 틀린 값이 채널에 남았을 때
 * 그것이 고쳐지기까지 걸리는 최대 시간이 이 주기다 — 짧게 잡으면 레이트 리밋을
 * 태우고, 길게 잡으면 틀린 상태가 오래 남는다.
 *
 * 대상 구간을 **앞으로 [HORIZON_DAYS]일**로 잡는다. 지난 날짜는 팔 수 없으므로
 * 채널 상태가 틀려도 손해가 없고, 너무 먼 미래는 격자 자체가 아직 없다.
 *
 * ## 주기와 한 바퀴는 다르다
 *
 * 한 주기는 상한만큼만 보낸다(`#71` 의 키셋 커서). 그래서 **전 구간이 덮이기까지
 * 걸리는 시간은 주기 × `ceil(격자 / 상한)`** 이고, 그것이 이 방어선의 실제
 * 회복 시간이다. `cycleCompleted` 가 참인 순간에만 한 바퀴가 끝난다.
 *
 * 인스턴스가 여러 대여도 **한 대만 돈다**(`#67` 의 임대). 임대를 못 잡은 주기는
 * `skipped` 로 보고되며, 그것은 실패가 아니다.
 */
@Component
@ConditionalOnProperty(name = ["resync.enabled"], havingValue = "true", matchIfMissing = true)
class InventoryResyncScheduler(
    private val resync: InventoryResyncService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${resync.delay-ms:86400000}")
    fun run() {
        val today = LocalDate.now()
        val report = resync.resync(today, today.plusDays(HORIZON_DAYS))
        // skipped 는 로그를 남기지 않는다. 인스턴스가 여러 대면 매 주기 대부분이
        // skipped 이고, 그것을 찍으면 진짜 신호가 묻힌다.
        if (report.sent > 0 || report.failed > 0) log.info("재동기화: {}", report)
    }

    companion object {
        const val HORIZON_DAYS = 90L
    }
}
