package dev.preagile.stayinventory.ops

import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 운영 콘솔 한 장 (`#80`).
 *
 * ## 왜 화면을 만드는가 -- 범위 결정을 뒤집는 것이 아니다
 *
 * `docs/02-scope.md` 는 프론트엔드를 범위 밖으로 뒀다. 그 판단의 대상은
 * **게스트·사장님이 쓰는 예약 화면**이고 지금도 만들지 않는다.
 *
 * 이것은 다른 물건이다. README §7 의 제목은 *"무엇을 보고 무엇을 누르나"* 인데
 * 그 답이 `curl` 이었다. **확인 업무를 없앤다고 적어 두고 확인하는 방법으로
 * 터미널을 요구하면 주장과 산출물이 어긋난다.** 지표를 읽고 DEAD 를 재투입하고
 * 어긋난 날짜에 재동기화를 거는 것은 **운영자의 일이지 개발자의 일이 아니다.**
 *
 * ## 왜 이 한 장뿐인가
 *
 * 빌드 단계도, 프레임워크도, 외부 요청도 없다. HTML 한 파일이고 서버는 그것을
 * 그대로 내보낸다. **화면을 늘리기 시작하면 이 저장소가 증명하려는 것에서
 * 멀어진다** -- 여기서 멈추는 것이 범위 판단이다.
 *
 * ## 왜 이 경로만 키를 요구하지 않는가
 *
 * 브라우저 주소창으로 여는 최상위 이동에는 **헤더를 붙일 수단이 없다.** 남는 선택은
 * 셋이고 둘은 더 나쁘다.
 *
 * | | 대가 |
 * |---|---|
 * | 키를 쿼리스트링에 | 브라우저 히스토리 · 액세스 로그 · `Referer` 로 샌다 |
 * | 키를 쿠키로 | `POST /ops/resync` 에 CSRF 방어가 새로 필요해진다 |
 * | **껍데기만 열기** | **여는 것은 데이터가 아니라 빈 문서다** |
 *
 * 그래서 껍데기만 연다. **이 응답에는 재고도, 예약도, 지표도 없다** -- 숫자는
 * 전부 브라우저가 `X-Ops-Key` 를 붙여 따로 가져온다. 키를 모르면 빈 화면과
 * 입력창만 본다. `OpsSecurityTest` 가 이 두 가지를 각각 검사한다 --
 * 껍데기가 키 없이 열리는 것과, **그 껍데기에 데이터가 없는 것.**
 */
@RestController
class OpsConsoleController {

    /**
     * 클래스패스에서 읽어 그대로 내보낸다.
     *
     * `static/` 에 두고 Spring 이 서빙하게 하지 않는 이유는 **예외 경로가 코드에
     * 보이지 않게 되기 때문**이다. 필터가 `/ops` 하위를 통째로 막는 마당에, 무엇이
     * 뚫려 있는지는 매핑에 드러나 있어야 한다.
     */
    @GetMapping(PATH, produces = [MediaType.TEXT_HTML_VALUE])
    fun console(): String =
        ClassPathResource(RESOURCE).inputStream.bufferedReader().use { it.readText() }

    companion object {
        const val PATH = "/ops/console"
        private const val RESOURCE = "ops-console.html"
    }
}
