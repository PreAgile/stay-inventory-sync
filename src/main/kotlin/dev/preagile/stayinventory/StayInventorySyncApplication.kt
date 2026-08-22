package dev.preagile.stayinventory

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class StayInventorySyncApplication

fun main(args: Array<String>) {
    runApplication<StayInventorySyncApplication>(*args)
}
