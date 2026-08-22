package dev.preagile.stayinventory.webhook

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 채널 웹훅 수신구.
 *
 * **여기서 도메인을 건드리지 않는다.** 적고, 2xx 를 주고, 끝낸다 (절대 규칙 9).
 * 처리하다 느려지면 채널은 그것을 실패로 보고 같은 알림을 또 보낸다 -- 수신과
 * 처리를 나누면 채널의 재시도 시계와 우리 처리 시간이 분리된다.
 *
 * **중복에도 2xx 를 준다** (절대 규칙 5). 4xx 를 주면 채널이 실패로 간주해
 * 최대 24시간 재시도한다. 멱등하게 처리했다면 그것은 성공이다.
 *
 * `inbound_message` INSERT **조차** 실패하면 2xx 를 줄 수 없다. 그때는 5xx 다 --
 * 채널이 재전송하는 것이 at-least-once 의 정상 동작이고, 우리가 지켜야 하는 것은
 * *"2xx 를 줬으면 최소한 적어는 뒀다"* 뿐이다.
 */
@RestController
@RequestMapping("/webhooks/{channel}")
class InboundWebhookController(
    private val recorder: InboundMessageRecorder,
) {

    @PostMapping("/reservations")
    fun receive(
        @PathVariable channel: String,
        @RequestBody webhook: ReservationWebhook,
    ): ResponseEntity<WebhookAck> =
        when (val outcome = recorder.record(channel, webhook)) {
            is RecordOutcome.Accepted ->
                ResponseEntity.accepted().body(WebhookAck("ACCEPTED", outcome.inboundMessageId))

            RecordOutcome.Duplicate ->
                ResponseEntity.ok(WebhookAck("DUPLICATE", null))
        }
}

/**
 * `ACCEPTED` 는 **처리했다는 뜻이 아니라 적었다는 뜻이다.**
 *
 * 202 를 주는 이유가 그것이다. 200 을 주면 채널 쪽에서 "반영됐다" 로 읽을 여지가
 * 생기는데, 반영은 워커가 나중에 한다.
 */
data class WebhookAck(val status: String, val inboundMessageId: Long?)
