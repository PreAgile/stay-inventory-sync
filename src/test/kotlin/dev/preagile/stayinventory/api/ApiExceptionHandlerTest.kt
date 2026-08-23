package dev.preagile.stayinventory.api

import dev.preagile.stayinventory.PostgresTestContainer
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.time.LocalDate

/**
 * 예외 → HTTP 매핑 (`#65`).
 *
 * ## 무엇이 잘못돼 있었나
 *
 * `IllegalArgumentException` 핸들러가 `ReservationController` 에만 있었다.
 * 그래서 `InventoryDiffController` 의 `require(from < to)` 는 **`400` 이 아니라
 * `500`** 이 됐다 — **운영자가 날짜를 뒤집으면 잘못된 입력이 장애로 보고된다.**
 *
 * 그리고 커넥션 풀 고갈 · DB 다운 · 락 타임아웃이 전부 `500` 이었다.
 * 호출부가 **"우리 잘못" 과 "잠시 후 다시" 를 구분할 수 없다.**
 *
 * ## 이 스펙이 지키는 것
 *
 * **엔드포인트가 늘 때 자동으로 적용되는가.** 컨트롤러마다 붙이는 구조는
 * 붙이는 것을 잊을 자리를 만들고, 그 자리가 실제로 있었다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestContainer::class)
class ApiExceptionHandlerTest(
    private val mockMvc: MockMvc,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)

    test("#65 diff 의 날짜 역전이 400 이다 — 잘못된 입력이 장애로 보고되지 않는다") {
        // Given / When: from 이 to 보다 뒤다
        val response = mockMvc.perform(
            get("/ops/inventory-diff")
                .param("propertyId", "1")
                .param("from", march1.plusDays(5).toString())
                .param("to", march1.toString()),
        ).andReturn().response

        // Then: 전역 핸들러가 없던 시절에는 500 이었다.
        // 500 은 "우리 버그" 라는 뜻이고, 운영자가 날짜를 뒤집은 것은 우리 버그가 아니다
        response.status shouldBe 400
        response.contentAsString shouldContain "BAD_REQUEST"
    }

    test("#65 예약 API 의 날짜 역전도 같은 형태로 400 이다 — 응답 모양이 갈리지 않는다") {
        // Given / When
        val body = """
            {
              "roomTypeId": 1,
              "checkIn": "${march1.plusDays(3)}",
              "checkOut": "$march1",
              "roomCount": 1,
              "channel": "CHANNEL_A",
              "channelReservationId": "E-1",
              "guestName": "김손님"
            }
        """.trimIndent()
        val response = mockMvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .post("/reservations")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(body),
        ).andReturn().response

        // Then: 컨트롤러마다 핸들러를 두면 응답 모양이 갈린다.
        // 예전에는 이쪽이 {"message": ...} 였고 다른 곳은 500 이었다
        response.status shouldBe 400
        response.contentAsString shouldContain "BAD_REQUEST"
    }

    test("#65 핸들러가 전역이므로 컨트롤러에 지역 핸들러가 없다") {
        // Given / When / Then: 지역 핸들러가 남아 있으면 그쪽이 먼저 잡아
        // 전역 핸들러의 응답 모양과 갈린다. 남기면 조용히 두 계약이 생긴다
        val local = ReservationController::class.java.declaredMethods
            .filter { m ->
                m.annotations.any {
                    it.annotationClass.simpleName == "ExceptionHandler"
                }
            }
        local.size shouldBe 0
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
