package dev.preagile.stayinventory

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * `@EnableScheduling` 은 Inbox 워커를 깨우기 위한 것이다.
 *
 * 스케줄러가 정합성을 담당하지 않는다 -- 처리 트랜잭션이 담당한다. 그래서
 * 스케줄러를 끄면 처리가 멈출 뿐 데이터가 어긋나지는 않는다.
 */
@EnableScheduling
@SpringBootApplication
class StayInventorySyncApplication

fun main(args: Array<String>) {
    runApplication<StayInventorySyncApplication>(*args)
}
