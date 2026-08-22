package dev.preagile.stayinventory.webhook

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.inventory.InventoryFixture
import dev.preagile.stayinventory.persistence.InboundMessageRepository
import dev.preagile.stayinventory.persistence.ReservationRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.time.LocalDate
import javax.sql.DataSource

/**
 * 웹훅 수신구에서 재고까지 **하나로 이어지는** 경로.
 *
 * 이 스펙의 관심사는 **상태 코드**다. 중복에 4xx 를 주면 채널이 실패로 간주해
 * 최대 24시간 재시도하고, 그 재시도가 다시 4xx 를 받는다 -- 아무도 이득이 없는
 * 루프가 24시간 돈다 (절대 규칙 5).
 *
 * 그리고 **수신 응답이 처리 완료를 뜻하지 않는다.** 202 를 주는 이유가 그것이다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestContainer::class)
class InboundWebhookApiTest(
    private val mockMvc: MockMvc,
    private val dataSource: DataSource,
    private val worker: InboundMessageWorker,
    private val inbound: InboundMessageRepository,
    private val reservations: ReservationRepository,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)
    val fixture = InventoryFixture(dataSource)

    beforeTest { fixture.wipe() }

    fun body(roomTypeId: Long, sequenceKey: String? = null): String {
        val seq = sequenceKey?.let { """"sequenceKey":"$it",""" } ?: ""
        return """
            {
              "event": "RESERVATION_CREATED",
              "channelReservationId": "R-3001",
              $seq
              "roomTypeId": $roomTypeId,
              "checkIn": "$march1",
              "checkOut": "${march1.plusDays(2)}",
              "roomCount": 1,
              "guestName": "김손님"
            }
        """.trimIndent()
    }

    fun send(roomTypeId: Long, sequenceKey: String? = null) =
        mockMvc.perform(
            post("/webhooks/CHANNEL_A/reservations")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(roomTypeId, sequenceKey)),
        ).andReturn().response

    test("웹훅을 받으면 202 를 주고 그 시점에는 아직 예약이 없다") {
        // Given
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)

        // When
        val response = send(roomTypeId)

        // Then: 202 는 "적었다" 이지 "반영했다" 가 아니다. 200 을 주면 채널 쪽에서
        // 반영으로 읽을 여지가 생기는데, 반영은 워커가 나중에 한다
        response.status shouldBe 202
        response.contentAsString shouldContain "ACCEPTED"

        inbound.count() shouldBe 1
        reservations.count() shouldBe 0
    }

    test("워커가 돌면 그때 예약과 재고가 움직인다") {
        // Given: 적어 둔 알림
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        send(roomTypeId)

        // When
        worker.processPending()

        // Then
        reservations.count() shouldBe 1
        fixture.sold(roomTypeId, march1) shouldBe 1
    }

    test("중복 웹훅에 2xx 를 준다 — 4xx 면 채널이 24시간 재시도한다") {
        // Given: 이미 받은 알림
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        send(roomTypeId).status shouldBe 202

        // When: 같은 알림이 또 온다
        val response = send(roomTypeId)

        // Then: 200 이다. 멱등하게 처리했다면 그것은 성공이다
        response.status shouldBe 200
        response.contentAsString shouldContain "DUPLICATE"
        inbound.count() shouldBe 1
    }

    test("처리가 끝난 뒤에 온 중복도 2xx 다") {
        // Given: 처리까지 끝난 예약
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        send(roomTypeId)
        worker.processPending()

        // When: 채널이 뒤늦게 한 번 더 보낸다
        val response = send(roomTypeId)

        // Then: 재고가 두 번 빠지지 않고 응답도 2xx 다
        response.status shouldBe 200
        fixture.sold(roomTypeId, march1) shouldBe 1
    }

    test("순서키가 달라 새 알림으로 적혀도 재고는 한 번만 빠진다") {
        // Given: 순서키를 주는 채널의 재전송
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        send(roomTypeId, sequenceKey = "rev-1").status shouldBe 202
        send(roomTypeId, sequenceKey = "rev-2").status shouldBe 202
        inbound.count() shouldBe 2

        // When
        worker.processPending()

        // Then: 수신 단계가 못 막은 것을 처리 단계가 막는다
        reservations.count() shouldBe 1
        fixture.sold(roomTypeId, march1) shouldBe 1
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
