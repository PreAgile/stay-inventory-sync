package dev.preagile.stayinventory.ops

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * 재고 부족으로 거절된 요청 수.
 *
 * **이 숫자가 이 저장소의 주장을 운영에서 확인하는 유일한 경로다.** 오버부킹이
 * 일어나지 않았다는 것은 관측되지 않는다 -- 일어나지 않은 일에는 로그가 없다.
 * 대신 *"막았다"* 를 센다.
 *
 * ## 인메모리다. 그리고 그것을 숨기지 않는다
 *
 * 재기동하면 0 으로 돌아간다. 즉 이 값은 누적 총량이 아니라 **"이 인스턴스가
 * 뜬 뒤로"** 다. 그래서 응답에 [since] 를 함께 싣는다 -- 기준 시점 없는 카운터는
 * 읽는 사람이 반드시 오해한다.
 *
 * 그렇게 두는 이유 셋.
 *
 * - **거절은 도메인 사건이 아니다.** 아무것도 바뀌지 않았으므로 남길 행이 없고,
 *   남기려고 테이블을 만들면 정상 트래픽이 쓰기를 유발한다
 * - 이 값의 쓰임은 **추세 관찰**이지 정산이 아니다
 * - 관측 인프라가 없는 환경에서 Micrometer 는 의존성만 늘리고 아무것도 보여 주지 않는다
 *
 * **재검토 조건**: 지표 저장소가 생기거나, 이 값을 SLA·정산 근거로 쓰기 시작하면
 * 그때는 인메모리가 아니라 밖으로 내보내야 한다.
 */
@Component
class OverbookingPreventedCounter {

    private val counter = AtomicLong()

    /** 이 인스턴스가 세기 시작한 시점. 카운터와 **반드시 함께** 읽어야 한다. */
    val since: Instant = Instant.now()

    val total: Long get() = counter.get()

    fun increment() {
        counter.incrementAndGet()
    }
}
