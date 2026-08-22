package dev.preagile.stayinventory.inventory

import com.fasterxml.jackson.databind.ObjectMapper
import dev.preagile.stayinventory.domain.ReservationStatus
import dev.preagile.stayinventory.persistence.DailyInventory
import dev.preagile.stayinventory.persistence.DailyInventoryRepository
import dev.preagile.stayinventory.persistence.OutboxEvent
import dev.preagile.stayinventory.persistence.OutboxEventRepository
import dev.preagile.stayinventory.persistence.Reservation
import dev.preagile.stayinventory.persistence.ReservationRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 재고를 바꾸는 **유일한 경로**.
 *
 * `daily_inventory.sold` 를 이 클래스 밖에서 건드리지 않는다. ArchUnit 이 그것을
 * 검사하며, 그 검사가 성립하려면 연관 매핑이 없어야 한다 -- 연관을 타고 들어간
 * 필드 변경은 정적 참조를 남기지 않고 UPDATE 를 만든다 (ADR-0008 · 절대 규칙 12).
 *
 * ## 이 클래스가 지키는 순서
 *
 * ```
 * [하나의 트랜잭션]
 *   ① 날짜 목록을 stay_date 오름차순으로 만든다        ← ReserveCommand.stayDates()
 *   ② 날짜마다 SELECT ... FOR UPDATE                   ← 직렬화 지점
 *   ③ 전 날짜 검증. 하나라도 안 되면 전부 롤백
 *   ④ sold += roomCount
 *   ⑤ reservation INSERT
 *   ⑥ outbox_event INSERT                             ← 같은 트랜잭션 (절대 규칙 3)
 *   COMMIT
 * ```
 *
 * **락 보유 구간에서 외부 I/O 를 부르지 않는다** (절대 규칙 4). 채널 호출은
 * 커밋 뒤 릴레이가 한다 -- ⑥ 이 그것을 구조적으로 보장한다. 여기서 채널을 부르면
 * 락을 쥔 채 네트워크를 기다리게 되고, 그 시간만큼 그 날짜의 모든 예약이 멈춘다.
 */
@Service
class InventoryService(
    private val inventories: DailyInventoryRepository,
    private val reservations: ReservationRepository,
    private val outbox: OutboxEventRepository,
    private val objectMapper: ObjectMapper,
) {

    @Transactional
    fun reserve(command: ReserveCommand): ReserveResult {
        // ① 오름차순 날짜. 정렬은 커맨드가 들고 있다 -- 여기서 부르는 것을
        //    잊을 수 있는 형태로 두지 않는다.
        val dates = command.stayDates()

        // ② 한 날짜씩 잠근다. 순서가 이 루프에 있다.
        //
        //    IN 절 하나로 잠그면 순서가 플래너의 손에 들어가고, 정렬을 지웠을 때
        //    T2 가 실패한다는 보장이 사라진다.
        val locked = LinkedHashMap<java.time.LocalDate, DailyInventory>()
        val unavailable = mutableListOf<Unavailable>()

        for (date in dates) {
            val row = inventories.lockForUpdate(command.roomTypeId, date)
            if (row == null) {
                unavailable += Unavailable(date, UnavailableReason.NO_GRID, null)
                continue
            }
            locked[date] = row
        }

        // ③ 전 날짜 검증. 잠근 뒤에 판정한다 -- 잠그기 전에 읽으면 읽은 값과
        //    바꾸는 값 사이에 다른 트랜잭션이 들어온다.
        for ((date, row) in locked) {
            if (row.remaining < command.roomCount) {
                unavailable += Unavailable(date, UnavailableReason.SOLD_OUT, row.remaining)
            }
        }

        if (unavailable.isNotEmpty()) {
            // 아직 아무것도 쓰지 않았다. 롤백할 것이 없고 잠근 행은 커밋 시 풀린다.
            // 부분 성공을 만들지 않으려면 검증이 전부 끝난 뒤에 쓰기 시작해야 한다.
            return ReserveResult.Rejected(unavailable.sortedBy { it.stayDate })
        }

        // ④ 차감. 여기서부터가 쓰기 구간이다.
        val now = Instant.now()
        locked.values.forEach {
            it.sold += command.roomCount
            it.updatedAt = now
        }

        // ⑤ 예약 저장
        val reservation = reservations.save(
            Reservation(
                roomTypeId = command.roomTypeId,
                checkIn = command.checkIn,
                checkOut = command.checkOut,
                status = ReservationStatus.CONFIRMED,
                roomCount = command.roomCount,
                channel = command.channel,
                channelReservationId = command.channelReservationId,
                guestName = command.guestName,
                createdAt = now,
                updatedAt = now,
            ),
        )

        // ⑥ 통보를 같은 트랜잭션에 적는다. 이 한 줄이 dual-write 를 없앤다.
        //
        //    날짜마다 하나씩 만든다. 채널 API 가 (룸타입, 날짜) 단위로 재고를 받고,
        //    #9 의 버전 스탬프도 그 키로 병합·순서 판정을 한다.
        locked.forEach { (date, row) ->
            outbox.save(
                OutboxEvent(
                    aggregateType = "DAILY_INVENTORY",
                    // 이 컬럼에 FK 가 없는 이유가 여기 있다. 재고의 키는 (룸타입, 날짜)
                    // 복합인데 컬럼은 BIGINT 하나다. 나머지 절반은 payload 에 있다.
                    aggregateId = row.roomTypeId,
                    eventType = "INVENTORY_CHANGED",
                    payload = objectMapper.writeValueAsString(
                        InventoryChangedPayload(
                            roomTypeId = row.roomTypeId,
                            stayDate = date,
                            sold = row.sold,
                            total = row.total,
                            remaining = row.remaining,
                            cause = "RESERVED",
                            reservationId = requireNotNull(reservation.id) { "저장 직후 id 가 없다" },
                        ),
                    ),
                    createdAt = now,
                    nextAttemptAt = now,
                ),
            )
        }

        return ReserveResult.Reserved(requireNotNull(reservation.id) { "저장 직후 id 가 없다" })
    }
}
