package dev.preagile.stayinventory.api

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.inventory.InventoryFixture
import dev.preagile.stayinventory.persistence.OutboxEventRepository
import dev.preagile.stayinventory.persistence.ReservationRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
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
 * 진입점에서 재고까지 **하나로 이어지는** 경로를 본다.
 *
 * 부품은 각각 통과했다 -- 전이표는 단위 테스트가, 차감은 `InventoryDeductionTest`
 * 가, 경합은 `InventoryConcurrencyTest` 가. 여기서 보는 것은 **조립이 깨지지
 * 않았는가** 하나다. 컨트롤러가 커맨드를 잘못 만들거나 결과를 잘못된 상태 코드로
 * 옮기면 아래 계층이 아무리 옳아도 밖에서는 틀린 시스템이다.
 *
 * 상태 코드가 이 스펙의 관심사다. 재고 부족에 400 을 주면 호출부가 요청을
 * 고쳐서 재시도하는데 고칠 것이 없어 무한히 돈다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestContainer::class)
class ReservationApiTest(
    private val mockMvc: MockMvc,
    private val dataSource: DataSource,
    private val reservations: ReservationRepository,
    private val outbox: OutboxEventRepository,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)
    val fixture = InventoryFixture(dataSource)

    beforeTest { fixture.wipe() }

    fun body(
        roomTypeId: Long,
        checkIn: LocalDate = march1,
        checkOut: LocalDate = march1.plusDays(2),
        roomCount: Int = 1,
        channel: String = "CHANNEL_A",
        channelReservationId: String? = "HM-1001",
    ): String {
        val channelIdField =
            channelReservationId?.let { """"channelReservationId":"$it",""" } ?: ""
        return """
            {
              "roomTypeId": $roomTypeId,
              "checkIn": "$checkIn",
              "checkOut": "$checkOut",
              "roomCount": $roomCount,
              "channel": "$channel",
              $channelIdField
              "guestName": "김손님"
            }
        """.trimIndent()
    }

    test("예약이 만들어지면 201 과 예약 번호가 나오고 재고가 줄어 있다") {
        // Given: 물리 10 인 2일 격자
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)

        // When
        val response = mockMvc.perform(
            post("/reservations").contentType(MediaType.APPLICATION_JSON)
                .content(body(roomTypeId, roomCount = 2)),
        ).andReturn().response

        // Then
        response.status shouldBe 201
        response.contentAsString shouldContain "reservationId"

        fixture.sold(roomTypeId, march1) shouldBe 2
        fixture.sold(roomTypeId, march1.plusDays(1)) shouldBe 2
        // 퇴실일은 그대로다
        fixture.sold(roomTypeId, march1.plusDays(2)) shouldBe 0

        // 통보까지 한 커밋 안에서 적혔다
        outbox.count() shouldBe 2
    }

    test("재고가 없으면 409 다 — 400 이 아니다") {
        // Given: 3/2 만 매진
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 5)
        fixture.markSoldOut(roomTypeId, march1.plusDays(1))

        // When
        val response = mockMvc.perform(
            post("/reservations").contentType(MediaType.APPLICATION_JSON)
                .content(body(roomTypeId)),
        ).andReturn().response

        // Then: 요청은 올바른데 지금 팔 수 없다는 뜻이다. 400 을 주면 호출부가
        // 요청을 고쳐 재시도하고, 고칠 것이 없으니 무한히 돈다
        response.status shouldBe 409
        response.contentAsString shouldContain "SOLD_OUT"
        response.contentAsString shouldContain "2026-03-02"
    }

    test("체크아웃이 체크인보다 앞서면 400 이다") {
        // Given
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 5)

        // When
        val response = mockMvc.perform(
            post("/reservations").contentType(MediaType.APPLICATION_JSON)
                .content(body(roomTypeId, checkIn = march1.plusDays(2), checkOut = march1)),
        ).andReturn().response

        // Then: 이쪽은 호출부가 고칠 수 있는 요청이다
        response.status shouldBe 400
    }

    test("직접 예약은 채널 예약번호를 서버가 채운다 — NULL 을 만들지 않는다") {
        // Given: channelReservationId 없이 들어오는 직접 예약
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 5)

        // When
        val response = mockMvc.perform(
            post("/reservations").contentType(MediaType.APPLICATION_JSON)
                .content(body(roomTypeId, channel = "DIRECT", channelReservationId = null)),
        ).andReturn().response

        // Then: 비워 두면 UNIQUE(channel, channel_reservation_id) 가 직접 예약에
        // 대해 아무것도 막지 못한다. 서버가 UUID 를 채워 그 구멍을 없앤다
        response.status shouldBe 201
        val saved = reservations.findAll().single()
        saved.channelReservationId shouldNotBe ""
        saved.channelReservationId.length shouldBe 36
    }

    test("같은 채널 예약번호를 두 번 보내면 두 번째는 500 이 아니라 DB 가 막는다") {
        // Given: 이미 들어온 예약
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 5)
        mockMvc.perform(
            post("/reservations").contentType(MediaType.APPLICATION_JSON)
                .content(body(roomTypeId)),
        ).andReturn().response.status shouldBe 201

        // When: 같은 채널 예약번호로 한 번 더
        val second = runCatching {
            mockMvc.perform(
                post("/reservations").contentType(MediaType.APPLICATION_JSON)
                    .content(body(roomTypeId)),
            ).andReturn().response.status
        }

        // Then: 이 슬라이스는 아직 멱등 처리를 하지 않는다. 중요한 것은
        // **재고가 두 번 빠지지 않았다**는 것이고, DB UNIQUE 가 그것을 막는다.
        // 중복을 2xx 로 흡수하는 것은 #5 의 일이다 (절대 규칙 5)
        second.isFailure shouldBe true
        fixture.sold(roomTypeId, march1) shouldBe 1
        reservations.count() shouldBe 1
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
