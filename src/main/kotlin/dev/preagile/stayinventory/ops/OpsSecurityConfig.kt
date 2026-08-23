package dev.preagile.stayinventory.ops

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter

/**
 * `/ops` 표면 인증 (`#74`).
 *
 * ## 왜 범위 밖 결정을 뒤집는가
 *
 * `docs/02-scope.md` 는 인증을 범위 밖으로 두고 *"필터 체인에 붙는 위치만 명확하면
 * 충분"* 이라고 적었다. **그 판단을 내릴 때의 `/ops` 는 읽기와 단일 행 갱신뿐이었다.**
 *
 * | | 비인증 호출의 대가 |
 * |---|---|
 * | 읽기 · 단일 행 갱신 | 데이터 노출 · 한 행 |
 * | **`POST /ops/resync`** | **외부 채널 레이트 리밋 소진** |
 * | **`GET /ops/outbox/dead`** | **`payload` 안의 게스트 정보** |
 *
 * 레이트 리밋은 이 프로젝트가 **병목으로 지목한 자원**(`docs/04`)이다.
 * **엔드포인트 하나가 범위 결정의 전제를 바꿨다.**
 *
 * 뒤집는 것이 아니라 **"붙을 위치" 를 실제로 붙이는 것**이다.
 *
 * ## 왜 최소 형태인가
 *
 * 정적 API 키 하나다. OAuth·JWT·역할은 여전히 범위 밖이고, 이 저장소가 증명하려는
 * 것과 직교한다. **막지 않는 것과 최소로 막는 것의 차이가 크고, 최소로 막는 것과
 * 제대로 막는 것의 차이는 이 프로젝트의 주장에 영향을 주지 않는다.**
 *
 * ## 도메인 경로는 건드리지 않는다
 *
 * `/reservations` 와 `/webhooks` 는 채널이 부르는 경로다. 여기에 키를 요구하면
 * 채널 연동 계약이 바뀐다 -- 그것은 파트너 계약의 영역이고 범위 밖이다.
 */
@Configuration
class OpsSecurityConfig(
    @Value("\${ops.api-key.value}") private val apiKey: String,
) {

    init {
        // 비어 있으면 뜨지 않는다. "설정을 잊으면 인증이 꺼진다" 는 형태를
        // 만들지 않는다 -- 꺼진 것을 아무도 모른다.
        require(apiKey.isNotBlank()) {
            "OPS_API_KEY 가 비어 있다. /ops 표면을 열어 두지 않으려면 반드시 준다"
        }
    }

    /**
     * `/ops` 하위 전체를 막는다. **패턴으로 막으므로 엔드포인트가 늘 때 자동으로
     * 적용된다** -- 목록으로 막으면 새 경로를 추가하는 것을 잊는다.
     */
    @Bean
    fun opsApiKeyFilter(): FilterRegistrationBean<OpsApiKeyFilter> =
        FilterRegistrationBean(OpsApiKeyFilter(apiKey)).apply {
            addUrlPatterns("/ops/*")
            order = 1
        }
}

/** 헤더 하나를 본다. 상수 시간 비교를 쓸 이유는 키가 짧지 않기 때문이다. */
class OpsApiKeyFilter(private val expected: String) : OncePerRequestFilter() {

    /**
     * 콘솔 껍데기 하나만 통과시킨다 (`#80`).
     *
     * **패턴이 아니라 정확히 일치하는 한 경로다.** `startsWith` 로 두면
     * `/ops/console-secrets` 같은 경로가 나중에 생겼을 때 조용히 함께 열린다.
     *
     * 이 응답에는 데이터가 없다 -- 숫자는 브라우저가 `X-Ops-Key` 를 붙여 따로
     * 가져온다. 여는 이유는 **최상위 이동에 헤더를 붙일 수단이 없기** 때문이고,
     * 대안(쿼리스트링 키 · 쿠키)이 더 나쁜 근거는 `OpsConsoleController` 에 적었다.
     */
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method == "GET" && request.requestURI == OpsConsoleController.PATH

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val given = request.getHeader(HEADER)
        if (given == null || !constantTimeEquals(given, expected)) {
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = "UTF-8"
            // 무엇이 필요한지 적는다. 401 만 주면 헤더 이름을 알 수 없다.
            response.writer.write(
                """{"status":"UNAUTHORIZED","reason":"$HEADER 헤더가 필요하다"}""",
            )
            return
        }
        filterChain.doFilter(request, response)
    }

    /**
     * 길이가 다르면 즉시 거짓이지만 **내용 비교는 상수 시간**으로 한다.
     * 조기 반환하면 일치하는 접두사 길이가 응답 시간으로 새어 나온다.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    companion object {
        const val HEADER = "X-Ops-Key"
    }
}
