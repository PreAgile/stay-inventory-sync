package dev.preagile.stayinventory.api

import dev.preagile.stayinventory.inventory.CancelResult
import dev.preagile.stayinventory.inventory.InventoryService
import dev.preagile.stayinventory.inventory.ReserveCommand
import dev.preagile.stayinventory.inventory.ReserveResult
import dev.preagile.stayinventory.inventory.Unavailable
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * 예약 생성 진입점.
 *
 * 인증·권한은 범위 밖이다(`docs/02-scope.md`). 이 컨트롤러가 하는 일은 요청을
 * [ReserveCommand] 로 옮기고 결과를 상태 코드로 옮기는 것뿐이며, **판단은 전부
 * [InventoryService] 안에 있다.** 컨트롤러가 재고를 보고 분기하기 시작하면
 * 그 분기는 락 밖에서 일어나므로 언제나 낡은 값을 본다.
 */
@RestController
@RequestMapping("/reservations")
class ReservationController(
    private val inventoryService: InventoryService,
) {

    @PostMapping
    fun create(@RequestBody @Valid request: CreateReservationRequest): ResponseEntity<Any> {
        val command = ReserveCommand(
            roomTypeId = request.roomTypeId,
            checkIn = request.checkIn,
            checkOut = request.checkOut,
            roomCount = request.roomCount,
            channel = request.channel,
            // 직접 예약은 내부 UUID 로 채운다. NULL 을 허용하면
            // UNIQUE(channel, channel_reservation_id) 가 직접 예약을 막지 못한다.
            channelReservationId = request.channelReservationId ?: UUID.randomUUID().toString(),
            guestName = request.guestName,
        )

        return when (val result = inventoryService.reserve(command)) {
            is ReserveResult.Reserved ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .body(CreateReservationResponse(result.reservationId))

            // 409. 요청은 올바른데 지금 팔 수 없다는 뜻이다. 400 을 주면
            // 호출부가 요청을 고쳐서 재시도하고, 고칠 것이 없으니 무한히 돈다.
            is ReserveResult.Rejected ->
                ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(RejectedResponse(result.unavailable))
        }
    }

    /**
     * 취소. **이미 취소된 예약에도 2xx 를 준다.**
     *
     * 4xx 를 주면 채널이 실패로 간주해 최대 24시간 재시도한다 (절대 규칙 5).
     * 멱등하게 처리했다면 그것은 성공이다. 없는 예약만 404 로 가른다 --
     * 그쪽은 데이터가 어긋났다는 신호이고, 재시도 노이즈에 묻히면 안 된다.
     */
    @PostMapping("/{id}/cancel")
    fun cancel(@PathVariable id: Long): ResponseEntity<Any> =
        when (val result = inventoryService.cancel(id)) {
            is CancelResult.Restored ->
                ResponseEntity.ok(CancelResponse("CANCELED", result.roomCount))

            CancelResult.AlreadyCanceled ->
                ResponseEntity.ok(CancelResponse("ALREADY_CANCELED", null))

            CancelResult.NotFound ->
                ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(mapOf("message" to "예약 $id 이 없다"))
        }

    /** `ReserveCommand` 의 `require` 가 던지는 것. 요청 자체가 틀렸으므로 400 이다. */
    @ExceptionHandler(IllegalArgumentException::class)
    fun onInvalidRequest(e: IllegalArgumentException): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(mapOf("message" to (e.message ?: "잘못된 요청")))
}

data class CreateReservationRequest(
    val roomTypeId: Long,
    val checkIn: LocalDate,
    val checkOut: LocalDate,
    @field:Min(1) val roomCount: Int = 1,
    @field:NotBlank val channel: String,
    /** 채널이 준 예약 번호. 직접 예약이면 비운다 -- 서버가 UUID 를 채운다. */
    val channelReservationId: String? = null,
    @field:NotBlank val guestName: String,
)

data class CreateReservationResponse(val reservationId: Long)

data class RejectedResponse(val unavailable: List<Unavailable>)

/** [restoredRoomCount] 는 실제로 되돌린 객실 수. 이미 취소된 건이면 null 이다. */
data class CancelResponse(val status: String, val restoredRoomCount: Int?)
