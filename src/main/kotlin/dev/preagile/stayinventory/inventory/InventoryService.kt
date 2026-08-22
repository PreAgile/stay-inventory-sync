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
                    // 이 컬럼에 FK 가 없는 이유가 여기 있다. 다형 참조이므로
                    // 대상 테이블을 하나로 고정할 수 없다.
                    aggregateId = row.roomTypeId,
                    // 순서 판정용 키와 버전. 이 트랜잭션이 그 재고 행 락을 쥐고
                    // 있으므로 버전은 같은 키 안에서 단조 증가한다 (ADR-0012).
                    roomTypeId = row.roomTypeId,
                    stayDate = date,
                    version = outbox.nextVersionFor(row.roomTypeId, date),
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

    /**
     * 취소하고 재고를 되돌린다. 차감의 거울상인데 **대칭이 아닌 지점이 하나** 있다.
     *
     * ```
     * [하나의 트랜잭션]
     *   ① reservation 조건부 UPDATE (WHERE status = 'CONFIRMED')  ← 락 순서의 첫 단계
     *   ② rowcount 0 이면 여기서 끝. 재고를 잠그지 않고 커밋한다   ← 멱등
     *   ③ 날짜 목록을 오름차순으로, 날짜마다 FOR UPDATE
     *   ④ sold -= roomCount
     *   ⑤ outbox_event INSERT                                     ← 같은 트랜잭션
     *   COMMIT
     * ```
     *
     * **`WHERE status = 'CONFIRMED'` 가 제외 목록 전체를 대신한다.**
     *
     * | 취소·종료가 오는 상태 | 복원 | 왜 |
     * |---|---|---|
     * | `CONFIRMED` | 한다 | 차감했고 아직 되돌리지 않았다 |
     * | `HELD` · `PENDING_APPROVAL` | 아니다 | **차감된 적이 없다.** 선점은 `INV-4` 가 따로 센다 |
     * | `EXPIRED` · `REJECTED` | 아니다 | 차감된 적이 없다 |
     * | `TERMINATED` | 아니다 | 그 날짜는 이미 소비됐다 |
     * | `CANCELED` | 아니다 | 이미 되돌렸다 |
     *
     * 조건 한 줄이 다섯 경우를 걸러내고, **상태가 늘어도 이 코드를 고치지 않는다** --
     * 새 상태는 자동으로 복원 대상에서 빠진다. `ADR-0010` 이 상태를 5개에서 9개로
     * 늘렸는데 이 조건이 그대로 유효한 것이 그 증거다 (절대 규칙 7).
     */
    @Transactional
    fun cancel(reservationId: Long): CancelResult {
        // ① 조건부 UPDATE. 이 문장이 예약 행 락을 잡고 WHERE 절이 중복을 걸러낸다.
        //    별도의 SELECT FOR UPDATE 를 두지 않는다 -- 나누면 두 트랜잭션이 모두
        //    CONFIRMED 를 읽고 둘 다 복원한다.
        val now = Instant.now()
        val moved = reservations.transition(
            id = reservationId,
            from = ReservationStatus.CONFIRMED,
            to = ReservationStatus.CANCELED,
            now = now,
        )

        // ② 옮기지 못했다. 재고를 잠그지 않고 끝낸다.
        if (moved == 0) {
            return if (reservations.existsById(reservationId)) {
                CancelResult.AlreadyCanceled
            } else {
                CancelResult.NotFound
            }
        }

        // 여기서 처음 읽는다. ① 이 이미 락을 잡았으므로 다른 트랜잭션이 이 행을
        // 바꿀 수 없다. bulk update 는 영속성 컨텍스트를 거치지 않으므로
        // 이 조회가 보는 것은 DB 의 현재 값이다.
        val reservation = reservations.findById(reservationId).orElseThrow {
            // ① 이 1을 돌려줬는데 행이 없다는 것은 같은 트랜잭션 안에서
            // 행이 사라졌다는 뜻이다. 조용히 넘기면 재고가 영영 안 돌아온다.
            IllegalStateException("전이는 성공했는데 예약 $reservationId 을 읽을 수 없다")
        }

        // ③ 오름차순으로 잠근다. 차감과 같은 순서여야 T6 이 성립한다.
        val restored = reservation.stayDates().map { date ->
            // 격자가 없으면 **전체를 롤백한다.** 처음에는 그 날짜만 건너뛰었는데,
            // 그러면 예약은 CANCELED 로 커밋되고 일부 날짜만 복원된 상태가 남는다.
            // 차감이 전 날짜의 격자를 요구하므로 이 상황은 누군가 격자 행을
            // 지웠다는 뜻이고, **조용히 넘길 것이 아니라 시끄러워야 하는 사건**이다.
            // 부분 복원은 되돌릴 방법도 없이 남는다.
            val row = inventories.lockForUpdate(reservation.roomTypeId, date)
                ?: throw IllegalStateException(
                    "복원할 재고 격자가 없다: 룸타입 ${reservation.roomTypeId} $date " +
                        "(예약 $reservationId)",
                )
            date to row
        }

        // ④ 복원. 1 이 아니라 room_count 다 (A7).
        restored.forEach { (_, row) ->
            row.sold -= reservation.roomCount
            row.updatedAt = now
        }

        // ⑤ 통보도 같은 트랜잭션에
        restored.forEach { (date, row) ->
            outbox.save(
                OutboxEvent(
                    aggregateType = "DAILY_INVENTORY",
                    aggregateId = row.roomTypeId,
                    roomTypeId = row.roomTypeId,
                    stayDate = date,
                    version = outbox.nextVersionFor(row.roomTypeId, date),
                    eventType = "INVENTORY_CHANGED",
                    payload = objectMapper.writeValueAsString(
                        InventoryChangedPayload(
                            roomTypeId = row.roomTypeId,
                            stayDate = date,
                            sold = row.sold,
                            total = row.total,
                            remaining = row.remaining,
                            cause = "CANCELED",
                            reservationId = reservationId,
                        ),
                    ),
                    createdAt = now,
                    nextAttemptAt = now,
                ),
            )
        }

        return CancelResult.Restored(reservationId, reservation.roomCount)
    }
}
