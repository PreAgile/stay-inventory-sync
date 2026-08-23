package dev.preagile.stayinventory.support

import dev.preagile.stayinventory.ops.OpsApiKeyFilter
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder

/**
 * 테스트가 쓰는 `/ops` 인증 키 (`#74`).
 *
 * `src/test/resources/application.properties` 의 값과 같아야 한다. 두 곳에 적는
 * 대신 한 곳을 참조하고 싶지만, 프로퍼티는 컨텍스트 로딩 시점 값이고 여기는
 * 컴파일 시점이다 -- **어긋나면 전부 401 이 되므로 조용히 틀리지는 않는다.**
 */
const val TEST_OPS_KEY = "test-ops-key-not-a-secret"

/**
 * `/ops` 요청에 인증 헤더를 붙인다.
 *
 * 각 테스트가 헤더를 직접 붙이면 **새 `/ops` 테스트가 그것을 잊을 자리가 생긴다.**
 * 잊으면 401 이 나서 즉시 드러나므로 조용한 실패는 아니지만, 매번 같은 줄을
 * 쓰게 만드는 구조를 두지 않는다.
 */
fun MockHttpServletRequestBuilder.withOpsKey(): MockHttpServletRequestBuilder =
    header(OpsApiKeyFilter.HEADER, TEST_OPS_KEY)
