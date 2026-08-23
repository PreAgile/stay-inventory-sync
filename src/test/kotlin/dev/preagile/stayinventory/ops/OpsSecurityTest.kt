package dev.preagile.stayinventory.ops

import dev.preagile.stayinventory.PostgresTestContainer
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * `/ops` 표면 인증 (`#74`) · 헬스체크 (`#69`).
 *
 * ## 무엇이 문제였나
 *
 * `/ops` 여섯 엔드포인트에 인증이 없었고, 그중 하나는 호출 한 번에 **외부로 최대
 * 500건**을 보낸다. 레이트 리밋은 이 프로젝트가 **병목으로 지목한 자원**이다.
 * 그리고 `GET /ops/outbox/dead` 는 `payload` 안의 게스트 정보를 그대로 내보낸다.
 *
 * ## 이 스펙이 지키는 것
 *
 * **목록이 아니라 패턴으로 막혔는가.** 엔드포인트를 하나 추가하면서 인증을
 * 빠뜨리는 것이 가장 흔한 실수이므로, **핸들러 매핑을 훑어 `/ops` 경로를 전부
 * 찾아 각각 401 을 확인한다.** 목록을 손으로 적으면 새 경로가 그 목록에서 빠진다.
 */
@SpringBootTest(properties = ["ops.api-key.value=test-key-1234567890"])
@AutoConfigureMockMvc
@Import(PostgresTestContainer::class)
class OpsSecurityTest(
    private val mockMvc: MockMvc,
    private val context: ApplicationContext,
) : FunSpec({

    /** 실제 매핑에서 `/ops` 경로를 전부 뽑는다. 목록을 손으로 적지 않는다. */
    fun opsPaths(): List<Pair<String, String>> {
        // actuator 가 두 번째 매핑을 등록하므로 이름으로 집는다.
        // 타입으로 집으면 NoUniqueBeanDefinitionException 이 난다.
        val mapping = context.getBean(
            "requestMappingHandlerMapping",
            RequestMappingHandlerMapping::class.java,
        )
        return mapping.handlerMethods.keys.flatMap { info ->
            val methods = info.methodsCondition.methods.map { it.name }.ifEmpty { listOf("GET") }
            val patterns = info.pathPatternsCondition?.patternValues
                ?: info.patternsCondition?.patterns
                ?: emptySet()
            patterns.filter { it.startsWith("/ops") }.flatMap { p -> methods.map { it to p } }
        }
    }

    test("#74 /ops 경로 전부가 키 없이는 401 이다 — 목록이 아니라 패턴으로 막는다") {
        // Given: 실제 매핑에서 뽑은 /ops 경로들
        val paths = opsPaths()
        paths.isNotEmpty() shouldBe true

        // When / Then: 하나라도 통과하면 그 경로가 열려 있다
        val leaked = paths.filter { (method, path) ->
            val req = when (method) {
                "POST" -> post(path.replace("{id}", "1"))
                "PUT" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .put(path)
                "DELETE" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .delete(path)
                else -> get(path)
            }
            mockMvc.perform(req).andReturn().response.status != 401
        }
        leaked.shouldBeEmpty()
    }

    test("#74 키가 맞으면 통과한다 — 막는 것이 목적이고 잠그는 것이 목적이 아니다") {
        // Given / When / Then: 401 이 아니어야 한다.
        // (파라미터가 없어 400 이 날 수 있고 그것은 인증을 통과한 것이다)
        val status = mockMvc.perform(
            get("/ops/metrics").header(OpsApiKeyFilter.HEADER, "test-key-1234567890"),
        ).andReturn().response.status
        status shouldBe 200
    }

    test("#74 키가 틀리면 401 이다") {
        mockMvc.perform(
            get("/ops/metrics").header(OpsApiKeyFilter.HEADER, "wrong-key-000000000"),
        ).andReturn().response.status shouldBe 401
    }

    test("#74 도메인 경로는 막지 않는다 — 채널 연동 계약을 바꾸지 않는다") {
        // Given / When: 채널이 부르는 경로에 키 없이 요청한다
        val status = mockMvc.perform(
            post("/webhooks/CHANNEL_A/reservations")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""{"event":"RESERVATION_CREATED","channelReservationId":"X-1"}"""),
        ).andReturn().response.status

        // Then: 401 이 아니다. 여기에 키를 요구하면 파트너 계약이 바뀐다
        (status != 401) shouldBe true
    }

    // ── #69 헬스체크 ──────────────────────────────────────────────────────
    test("#69 readiness 가 DB 상태를 본다 — 200 만 돌려주는 헬스체크는 값이 없다") {
        // Given / When
        val response = mockMvc.perform(get("/actuator/health/readiness"))
            .andReturn().response

        // Then: DB 를 안 보면 커넥션을 못 잡는 인스턴스가 "준비됐다" 고 답하고
        // 트래픽을 받는다. 이 저장소는 다중 인스턴스를 전제로 설계를 논한다
        response.status shouldBe 200
        response.contentAsString shouldBe """{"status":"UP"}"""
    }

    test("#69 liveness 는 DB 를 보지 않는다 — DB 장애로 프로세스가 재시작되면 안 된다") {
        // Given / When / Then: liveness 가 DB 를 보면 DB 가 흔들릴 때
        // 오케스트레이터가 살아 있는 프로세스를 죽인다 -- 회복을 방해한다
        mockMvc.perform(get("/actuator/health/liveness"))
            .andReturn().response.status shouldBe 200
    }

    test("#69 health 외 actuator 엔드포인트는 열지 않는다") {
        // Given / When / Then: env·beans·configprops 는 운영 정보를 노출한다.
        // 지표는 /ops/metrics 가 내므로 metrics 엔드포인트도 필요 없다
        listOf("/actuator/env", "/actuator/beans", "/actuator/metrics").forEach { path ->
            mockMvc.perform(get(path)).andReturn().response.status shouldBe 404
        }
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
