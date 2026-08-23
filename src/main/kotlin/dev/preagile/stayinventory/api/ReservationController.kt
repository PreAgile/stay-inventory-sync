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
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.dao.DataIntegrityViolationException
import dev.preagile.stayinventory.persistence.ReservationRepository
import org.springframework.web.bind.annotation.RequestHeader
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
    private val reservations: ReservationRepository,
) {

    /**
     * 예약 생성. **멱등 키를 호출부가 준다** (ADR-0014).
     *
     * 채널 예약은 `channelReservationId`, 직접 예약은 `Idempotency-Key` 헤더다.
     * **둘 다 없으면 400 이고 서버가 대신 만들지 않는다** -- 서버가 키를 만들면
     * 요청마다 값이 달라져 `UNIQUE` 가 재시도를 식별할 수 없다.
     */
    @PostMapping
    fun create(
        @RequestBody @Valid request: CreateReservationRequest,
        @RequestHeader(name = "Idempotency-Key", required = false) idempotencyKey: String?,
    ): ResponseEntity<Any> {
        val key = request.channelReservationId?.takeIf { it.isNotBlank() }
            ?: idempotencyKey?.takeIf { it.isNotBlank() }
            ?: return ResponseEntity.badRequest().body(
                MissingIdempotencyKeyResponse(),
            )

        val command = ReserveCommand(
            roomTypeId = request.roomTypeId,
            checkIn = request.checkIn,
            checkOut = request.checkOut,
            roomCount = request.roomCount,
            channel = request.channel,
            channelReservationId = key,
            guestName = request.guestName,
        )

        // 멱등 2층 -- 경합으로 조기 조회가 뚫린 경우 DB UNIQUE 가 막는다.
        //
        // **트랜잭션 밖에서 잡아야 한다.** 서비스 안에서 잡으면 그 트랜잭션은 이미
        // rollback-only 로 표시돼 있어 커밋 시점에 다시 터진다 (Inbox 와 같은 이유).
        val result = try {
            inventoryService.reserve(command)
        } catch (e: DataIntegrityViolationException) {
            // 다른 요청이 먼저 넣었다. 그쪽 결과를 돌려준다 -- 재시도가
            // 첫 시도와 같은 답을 받는 것이 이 계약의 전부다.
            val existing = reservations.findByChannelAndChannelReservationId(
                command.channel,
                command.channelReservationId,
            ) ?: throw e
            ReserveResult.Duplicate(requireNotNull(existing.id))
        }

        return when (result) {
            is ReserveResult.Reserved ->
                ResponseEntity.status(HttpStatus.CREATED)
                    .body(CreateReservationResponse(result.reservationId))

            // 200. 재시도가 **첫 시도와 같은 답**을 받는다. 409 를 주면 채널이
            // 실패로 보고 최대 24시간 재시도한다 (절대 규칙 5).
            is ReserveResult.Duplicate ->
                ResponseEntity.ok(CreateReservationResponse(result.reservationId))

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
    // IllegalArgumentException 핸들러를 여기 두지 않는다. ApiExceptionHandler 로
    // 올렸다 -- 컨트롤러마다 붙이는 구조는 붙이는 것을 잊을 자리를 만들고,
    // 실제로 InventoryDiffController 의 require 가 400 이 아니라 500 이었다 (#65).
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

/**
 * `400` 의 본문. **무엇을 해야 하는지까지 적는다** -- 상태코드만으로는
 * "키를 달라" 는 것을 알 수 없다.
 */
data class MissingIdempotencyKeyResponse(
    val status: String = "MISSING_IDEMPOTENCY_KEY",
    val reason: String =
        "channelReservationId 또는 Idempotency-Key 헤더가 필요하다. " +
            "서버가 키를 만들면 재시도마다 값이 달라져 중복 예약이 생긴다",
)
