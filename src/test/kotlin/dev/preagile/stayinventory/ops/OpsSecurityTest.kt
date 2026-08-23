package dev.preagile.stayinventory.ops

import dev.preagile.stayinventory.PostgresTestContainer
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.springframework.boot.actuate.health.HealthEndpointGroups
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

    /**
     * 키 없이 열리는 것은 **콘솔 껍데기 하나뿐**이다 (`#80`).
     *
     * 이 집합을 테스트에 박아 두는 이유는, 예외를 늘리는 순간 아래 두 스펙이 **함께**
     * 깨지게 하기 위해서다 -- 새 예외는 여기 적히지 않으면 401 스펙에서 걸리고,
     * 여기 적으면 "예외는 정확히 하나" 스펙에서 걸린다.
     */
    val exempt = setOf("GET" to OpsConsoleController.PATH)

    test("#80 키 없이 열리는 /ops 경로는 콘솔 껍데기 하나뿐이다") {
        // Given: 실제 매핑에서 뽑은 /ops 경로들
        // When: 키 없이 전부 부른다
        val open = opsPaths().filter { (method, path) ->
            val req = when (method) {
                "POST" -> post(path.replace("{id}", "1"))
                "PUT" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .put(path)
                "DELETE" -> org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .delete(path)
                else -> get(path)
            }
            mockMvc.perform(req).andReturn().response.status != 401
        }.toSet()

        // Then: 열린 집합이 선언한 예외와 **정확히 같다**.
        // 부분집합으로 두면 예외를 늘려도 통과한다
        open shouldBe exempt
    }

    test("#80 콘솔 껍데기는 키 없이 열리지만 그 안에 데이터가 없다") {
        // Given / When: 키 없이 콘솔을 연다
        val res = mockMvc.perform(get(OpsConsoleController.PATH)).andReturn().response

        // Then: 200 HTML 이다. 브라우저 최상위 이동에는 헤더를 붙일 수단이 없다
        res.status shouldBe 200
        res.contentType!!.startsWith("text/html") shouldBe true

        // Then: **여는 것은 빈 문서다.** 숫자는 브라우저가 키를 붙여 따로 가져온다.
        // 서버가 값을 끼워 넣기 시작하면 이 예외가 곧 데이터 유출 경로가 된다
        val body = res.contentAsString
        body.contains("X-Ops-Key") shouldBe true
        listOf("overbookingPrevented\"", "\"pending\"", "\"dead\"", "outbox_event")
            .none { body.contains(it) } shouldBe true
    }

    test("#74 /ops 경로 전부가 키 없이는 401 이다 — 목록이 아니라 패턴으로 막는다") {
        // Given: 실제 매핑에서 뽑은 /ops 경로들 (콘솔 껍데기 제외 — #80)
        val paths = opsPaths().filterNot { it in exempt }
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
        // Given: 설정된 키와 다른 값
        // When: /ops 를 호출한다
        // Then: 401. 길이가 같은 다른 키도 막혀야 한다 --
        // 비교가 접두사만 보면 앞부분이 맞는 키가 통과한다
        mockMvc.perform(
            get("/ops/metrics").header(OpsApiKeyFilter.HEADER, "wrong-key-000000000"),
        ).andReturn().response.status shouldBe 401
    }

    test("#74 플레이스홀더 리터럴은 키가 아니다 — 설정된 것처럼 보이는 미설정을 막는다") {
        // Given: escape 가 섞이면 Spring 이 ${'$'}{OPS_API_KEY:} 를 **리터럴 문자열**로
        // 넘겨준다. isNotBlank() 가 통과하므로 앱은 뜨고, 그 리터럴이 곧 키가 된다
        // When: 그 문자열을 헤더로 보낸다
        val status = mockMvc.perform(
            get("/ops/metrics").header(OpsApiKeyFilter.HEADER, "${'$'}{OPS_API_KEY:}"),
        ).andReturn().response.status

        // Then: 401. 통과하면 **누구나 그 문자열로 들어온다** --
        // 미설정보다 나쁘다. 설정된 것처럼 보이기 때문이다
        status shouldBe 401
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
    test("#69 readiness 그룹이 db 를 실제로 포함한다 — UP 만 보면 확인이 안 된다") {
        // Given: readiness 그룹 설정
        // When: HealthEndpointGroups 에서 그 그룹의 구성원을 읽는다
        val groups = context.getBean(HealthEndpointGroups::class.java)
        val readiness = groups.get("readiness")

        // Then: db 가 없으면 DB 가 죽어도 readiness 가 UP 을 답한다.
        //
        // **응답이 200/UP 인 것만 보면 이것을 확인할 수 없다** --
        // management.health.db.enabled 는 indicator 만 등록하고, readiness 그룹의
        // 기본 구성원은 readinessState 하나다. 첫 판이 그 상태로 통과했다
        readiness shouldNotBe null
        readiness!!.isMember("db") shouldBe true
        readiness.isMember("readinessState") shouldBe true
    }

    test("#69 liveness 그룹은 db 를 포함하지 않는다") {
        // Given / When
        val groups = context.getBean(HealthEndpointGroups::class.java)
        val liveness = groups.get("liveness")

        // Then: 포함하면 DB 장애로 오케스트레이터가 살아 있는 프로세스를 죽여
        // 회복을 방해한다. 죽일 이유와 트래픽을 끊을 이유는 다르다
        liveness shouldNotBe null
        liveness!!.isMember("db") shouldBe false
    }

    test("#69 readiness 엔드포인트가 응답한다") {
        // Given / When / Then: 그룹 구성이 맞아도 엔드포인트가 안 열려 있으면
        // 오케스트레이터가 부를 곳이 없다
        mockMvc.perform(get("/actuator/health/readiness"))
            .andReturn().response.status shouldBe 200
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
