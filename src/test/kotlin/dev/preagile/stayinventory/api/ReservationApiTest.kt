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

    test("#64 멱등 키가 없으면 400 이다 — 서버가 대신 만들지 않는다") {
        // Given: channelReservationId 도 Idempotency-Key 도 없는 직접 예약
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 5)

        // When
        val response = mockMvc.perform(
            post("/reservations").contentType(MediaType.APPLICATION_JSON)
                .content(body(roomTypeId, channel = "DIRECT", channelReservationId = null)),
        ).andReturn().response

        // Then: 서버가 UUID 를 채우던 시절에는 201 이 나왔고, **재시도마다 키가
        // 달라져 중복 예약이 생겼다.** 키를 만들 수 있는 것은 호출부뿐이다
        response.status shouldBe 400
        reservations.count() shouldBe 0
    }

    test("#64 Idempotency-Key 헤더로 직접 예약을 만든다") {
        // Given / When
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 5)
        val response = mockMvc.perform(
            post("/reservations").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "req-77")
                .content(body(roomTypeId, channel = "DIRECT", channelReservationId = null)),
        ).andReturn().response

        // Then: 헤더 값이 그대로 멱등 키가 된다.
        // 이름이 그 값의 용도를 말하는 것이 channelReservationId 를 강요하는 것보다 낫다
        response.status shouldBe 201
        reservations.findAll().single().channelReservationId shouldBe "req-77"
    }

    test("#64 응답을 못 받아 재시도하면 첫 시도와 같은 답을 받는다 — 중복 예약이 생기지 않는다") {
        // Given: 첫 시도가 성공했다 (호출부는 응답을 못 받았다고 가정)
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 5)
        val first = mockMvc.perform(
            post("/reservations").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "req-88")
                .content(body(roomTypeId, channel = "DIRECT", channelReservationId = null)),
        ).andReturn().response
        first.status shouldBe 201

        // When: **같은 키로** 재시도한다
        val retry = mockMvc.perform(
            post("/reservations").contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "req-88")
                .content(body(roomTypeId, channel = "DIRECT", channelReservationId = null)),
        ).andReturn().response

        // Then: 200 이고 같은 예약 번호다. 409 를 주면 채널이 실패로 보고
        // 최대 24시간 재시도한다 (절대 규칙 5)
        retry.status shouldBe 200
        retry.contentAsString shouldBe first.contentAsString

        // And: 재고가 두 번 빠지지 않았다 -- 이것이 이 이슈의 실제 피해였다
        fixture.sold(roomTypeId, march1) shouldBe 1
        reservations.count() shouldBe 1
    }

    test("#64 같은 채널 예약번호를 두 번 보내면 200 으로 흡수한다") {
        // Given: 이미 들어온 채널 예약
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 5)
        mockMvc.perform(
            post("/reservations").contentType(MediaType.APPLICATION_JSON)
                .content(body(roomTypeId)),
        ).andReturn().response.status shouldBe 201

        // When: 같은 번호로 한 번 더
        val second = mockMvc.perform(
            post("/reservations").contentType(MediaType.APPLICATION_JSON)
                .content(body(roomTypeId)),
        ).andReturn().response

        // Then: 500 이던 시절에는 재고는 지켜졌지만 **계약이 지켜지지 않았다** --
        // 채널이 500 을 실패로 보고 계속 재시도했다
        second.status shouldBe 200
        fixture.sold(roomTypeId, march1) shouldBe 1
        reservations.count() shouldBe 1
    }

    test("#64 다른 채널이 같은 키를 보내면 서로 다른 예약이다") {
        // Given / When: 같은 Idempotency-Key, 다른 채널
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 5)
        mockMvc.perform(
            post("/reservations").contentType(MediaType.APPLICATION_JSON)
                .content(body(roomTypeId, channel = "CHANNEL_A", channelReservationId = "K-1")),
        ).andReturn().response.status shouldBe 201
        val other = mockMvc.perform(
            post("/reservations").contentType(MediaType.APPLICATION_JSON)
                .content(body(roomTypeId, channel = "CHANNEL_B", channelReservationId = "K-1")),
        ).andReturn().response

        // Then: 키는 (channel, channel_reservation_id) 다. 채널 하나로만 보면
        // 서로 다른 채널의 예약이 하나로 뭉개진다
        other.status shouldBe 201
        reservations.count() shouldBe 2
    }
    // ── 취소 ──────────────────────────────────────────────────────────────
    test("취소하면 200 과 되돌린 객실 수가 나오고 재고가 돌아온다") {
        // Given: 2객실 2박 예약
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        val created = mockMvc.perform(
            post("/reservations").contentType(MediaType.APPLICATION_JSON)
                .content(body(roomTypeId, roomCount = 2)),
        ).andReturn().response.contentAsString
        val reservationId = requireNotNull(Regex("\\d+").find(created)) {
            "생성 응답에서 예약 번호를 찾지 못했다: $created"
        }.value

        // When
        val response = mockMvc.perform(post("/reservations/$reservationId/cancel"))
            .andReturn().response

        // Then
        response.status shouldBe 200
        response.contentAsString shouldContain "CANCELED"
        // 되돌린 객실 수가 응답에 실린다. 테스트 이름과 계약이 그것을 말하는데
        // 검증하지 않으면 계약이 아니라 희망이다
        response.contentAsString shouldContain "\"restoredRoomCount\":2"
        // 두 숙박일 모두 본다. 첫 날짜만 보면 둘째 날 복원이 빠져도 통과한다
        fixture.sold(roomTypeId, march1) shouldBe 0
        fixture.sold(roomTypeId, march1.plusDays(1)) shouldBe 0
    }

    test("이미 취소된 예약을 또 취소해도 200 이다 — 채널 재시도를 유발하지 않는다") {
        // Given: 취소된 예약
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        val created = mockMvc.perform(
            post("/reservations").contentType(MediaType.APPLICATION_JSON)
                .content(body(roomTypeId)),
        ).andReturn().response.contentAsString
        val reservationId = requireNotNull(Regex("\\d+").find(created)) {
            "생성 응답에서 예약 번호를 찾지 못했다: $created"
        }.value
        mockMvc.perform(post("/reservations/$reservationId/cancel"))

        // When: 같은 취소가 또 온다
        val response = mockMvc.perform(post("/reservations/$reservationId/cancel"))
            .andReturn().response

        // Then: 4xx 를 주면 채널이 실패로 간주해 최대 24시간 재시도한다.
        // 멱등하게 처리했다면 그것은 성공이다 (절대 규칙 5)
        response.status shouldBe 200
        response.contentAsString shouldContain "ALREADY_CANCELED"
        fixture.sold(roomTypeId, march1) shouldBe 0
    }

    test("없는 예약을 취소하면 404 다 — 정상 재시도와 구분된다") {
        // Given: 아무 예약도 없는 상태
        // When: 없는 번호로 취소를 요청한다
        val response = mockMvc.perform(post("/reservations/999999/cancel"))
            .andReturn().response

        // Then: 뭉치면 데이터가 어긋났다는 진짜 신호가 재시도 노이즈에 묻힌다
        response.status shouldBe 404
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
