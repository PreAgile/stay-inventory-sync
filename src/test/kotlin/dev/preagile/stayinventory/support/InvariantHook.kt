package dev.preagile.stayinventory.support

import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * 어느 스펙이 `INV-2` 를 면제받는지 **타입에 드러낸다.**
 *
 * 스키마를 시험하는 스펙은 도메인 연산 없이 행을 직접 넣는다 -- 예약 없이 `sold`
 * 만 있는 재고 행 같은 것. `INV-2`(카운터와 예약 사실의 대조)가 성립할 이유가 없다.
 *
 * 면제 수단으로 플래그나 애노테이션을 쓰지 않았다. 둘 다 **끄고 안 켜도 초록불**이고,
 * 리뷰에서 보이지 않는다. 인터페이스 구현은 클래스 선언 줄에 남아 diff 에 뜬다.
 * 그리고 면제 목록 자체를 [InvariantHookReachabilityTest] 가 고정한다 --
 * 새 면제는 그 목록을 함께 고쳐야 들어온다.
 */
interface DirectRowSpec {
    /** 왜 도메인 연산을 거치지 않는가. 비어 있으면 면제가 거부된다. */
    val inv2ExemptionReason: String
}

/**
 * 테스트 하나가 끝날 때마다 불변식을 검사하는 훅.
 *
 * **두 엔진에 각각 걸어야 한다.** Kotest 확장으로만 만들면 JUnit 스타일 테스트는
 * 검사를 조용히 건너뛴다 -- 통과 신호는 그대로 나오고 검사만 사라진다.
 * 그것이 이 저장소가 막겠다고 선언한 실패 형태다 (`AGENTS.md` 테스트 규약).
 *
 * 의존성 배제로는 막을 수 없다. `kotest-runner-junit5` 가 `junit-bom` 을 통해
 * Jupiter API 를 끌어오므로 Jupiter 는 항상 클래스패스에 있다. 그래서 **막지 않고
 * 양쪽에 건다.**
 */
object InvariantHook {

    enum class Engine { KOTEST, JUNIT }

    private val checks = mutableMapOf(Engine.KOTEST to AtomicInteger(), Engine.JUNIT to AtomicInteger())
    private val skips = AtomicInteger()

    @Volatile
    private var dataSource: DataSource? = null

    /**
     * DB 를 쓰는 컨텍스트가 뜰 때 [PostgresTestContainerHookArming] 이 호출한다.
     *
     * 한 번 무장되면 JVM 이 끝날 때까지 유지된다. Gradle 이 `maxParallelForks = 1`
     * 로 돌므로 DB 스펙이 하나라도 먼저 돌면 그 뒤의 순수 단위 테스트도 검사를 받는다 --
     * 불변식은 그 시점에도 참이어야 하므로 이것은 부작용이 아니라 이득이다.
     */
    fun arm(dataSource: DataSource) {
        this.dataSource = dataSource
    }

    fun checkCount(engine: Engine): Int = checks.getValue(engine).get()

    fun skipCount(): Int = skips.get()

    /**
     * @param spec 테스트 인스턴스. [DirectRowSpec] 이면 `INV-2` 를 면제한다
     */
    fun verify(spec: Any?, engine: Engine) {
        val ds = dataSource
        if (ds == null) {
            // DB 컨텍스트가 아직 한 번도 뜨지 않았다. 검사할 대상이 없다.
            // 조용히 넘기지 않고 세어 둔다 -- 무장 경로가 깨지면 이 숫자만 늘어난다.
            skips.incrementAndGet()
            return
        }

        checks.getValue(engine).incrementAndGet()

        if (spec is DirectRowSpec) {
            require(spec.inv2ExemptionReason.isNotBlank()) {
                "${spec.javaClass.simpleName} 이 INV-2 면제 사유를 비워 두었다"
            }
            InventoryInvariants.assertExceptInv2(ds)
        } else {
            InventoryInvariants.assertAll(ds)
        }
    }
}
