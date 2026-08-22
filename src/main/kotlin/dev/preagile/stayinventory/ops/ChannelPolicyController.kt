package dev.preagile.stayinventory.ops

import dev.preagile.stayinventory.policy.ChannelPolicyService
import dev.preagile.stayinventory.policy.ChannelPolicyView
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/**
 * 채널별 노출 상한 운영 API. **화면은 범위 밖이다** (`docs/02-scope.md`).
 *
 * `ADR-0007` 이 오버부킹 한도에 쓴 것과 같은 경계다 — 값을 정하는 UI 는 만들지
 * 않고, **그 값이 시스템에 어떻게 들어와 어떻게 투영되는지**까지가 이 저장소의 범위다.
 */
@RestController
@RequestMapping("/ops/channel-policy")
class ChannelPolicyController(
    private val policies: ChannelPolicyService,
) {

    /** 캡을 걸거나 값을 바꾼다. 멱등이다 — 같은 값을 두 번 넣어도 결과가 같다. */
    @PutMapping
    fun setCap(@RequestBody @Valid request: SetCapRequest): ResponseEntity<Any> {
        policies.setCap(request.roomTypeId, request.stayDate, request.channel, request.value)
        return ResponseEntity.ok(mapOf("status" to "APPLIED"))
    }

    /**
     * 캡을 해제한다.
     *
     * 없던 것을 지우는 요청은 **404 다.** 200 을 주면 운영자가 "해제했다" 고
     * 믿는데 실제로는 애초에 걸린 적이 없다 — 다른 날짜나 다른 채널에 건 것을
     * 지우려 했을 가능성이 크고, 그 실수를 여기서 알려야 한다.
     */
    @DeleteMapping
    fun removeCap(
        @RequestParam roomTypeId: Long,
        @RequestParam stayDate: LocalDate,
        @RequestParam channel: String,
    ): ResponseEntity<Any> =
        if (policies.removeCap(roomTypeId, stayDate, channel)) {
            ResponseEntity.ok(mapOf("status" to "REMOVED"))
        } else {
            ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(mapOf("message" to "걸린 캡이 없다"))
        }

    @GetMapping
    fun list(
        @RequestParam propertyId: Long,
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate,
    ): List<ChannelPolicyView> = policies.list(propertyId, from, to)
}

data class SetCapRequest(
    val roomTypeId: Long,
    val stayDate: LocalDate,
    @field:NotBlank val channel: String,
    /** 0 은 유효하다 — 그 채널에 안 팔겠다는 뜻이며 `CLOSED` 와 결과가 같다. */
    @field:Min(0) val value: Int,
)
