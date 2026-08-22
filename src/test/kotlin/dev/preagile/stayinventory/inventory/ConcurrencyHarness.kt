package dev.preagile.stayinventory.inventory

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * [count] 개 작업을 **동시에 출발시킨다.** 순차 실행이면 경합을 증명하지 못한다.
 *
 * **스레드에서 새어 나온 예외를 삼키지 않는다.** `submit` 한 작업의 예외는
 * `Future` 안에 갇혀 아무 데도 나타나지 않는다 -- 테스트는 초록불인데 절반이
 * 예외로 죽어 있을 수 있고, 그 상태로 나온 숫자를 근거로 "정확히 1건" 을 주장하게 된다.
 *
 * **이 저장소에서 실제로 그 일이 있었다.** 조건부 UPDATE 의
 * `AND status = 'CONFIRMED'` 를 제거해도 「T5 확장」이 통과했는데, 복원이 한 번만
 * 일어나서가 아니라 초과 복원이 `sold >= 0` CHECK 에 걸려 예외로 죽고 그 예외가
 * 사라졌기 때문이었다. 반증 실험이 통과하면 그 테스트는 아무것도 증명하지 않는다.
 *
 * 예외를 기대하는 테스트는 작업 안에서 `runCatching` 으로 직접 받는다.
 */
fun runConcurrentlyOrFail(count: Int, task: (Int) -> Unit) {
    val pool = Executors.newFixedThreadPool(count)
    val startGate = CountDownLatch(1)
    val done = CountDownLatch(count)
    val escaped = ConcurrentLinkedQueue<Throwable>()
    try {
        repeat(count) { index ->
            pool.submit {
                // await 도 try 안에 있어야 한다. 밖에 두면 여기서 인터럽트가 났을 때
                // done 이 감소하지 않고, 그러면 아래 대기가 120초를 다 쓴 뒤
                // "끝나지 않았다" 는 **원인과 다른 메시지**로 실패한다.
                try {
                    startGate.await()
                    task(index)
                } catch (e: Throwable) {
                    escaped += e
                } finally {
                    done.countDown()
                }
            }
        }
        startGate.countDown()
        check(done.await(120, TimeUnit.SECONDS)) { "동시 작업이 120초 안에 끝나지 않았다" }
    } finally {
        pool.shutdownNow()
    }
    if (escaped.isNotEmpty()) {
        throw AssertionError(
            "스레드에서 예외 ${escaped.size}건이 새어 나왔다\n" +
                escaped.take(5).joinToString("\n") { "  - $it" },
        )
    }
}
