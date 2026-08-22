package dev.preagile.stayinventory.persistence

import dev.preagile.stayinventory.domain.ReservationStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.time.LocalDate

/**
 * 테이블로 가는 유일한 경로.
 *
 * 조회 메서드를 미리 채우지 않는다. 이 시스템에서 JPA 는 객체 그래프 도구가
 * 아니라 **행 매퍼**이고(ADR-0008), 쓰는 사람이 없는 쿼리는 어떤 락을 잡는지
 * 아무도 판단하지 않은 채로 남는다. 락이 필요한 조회는 그것을 쓰는 슬라이스에서
 * 잠금 모드와 함께 들어온다.
 *
 * **Outbox 릴레이는 이 파일을 쓰지 않는다.** 폴링은 `FOR UPDATE SKIP LOCKED` 로
 * 내려가야 하고, JPA 표준에 없는 쿼리를 리포지토리 관례로 위장하지 않는다
 * (ADR-0008 결정 3).
 */
interface PropertyRepository : JpaRepository<Property, Long>

interface RoomTypeRepository : JpaRepository<RoomType, Long>

interface DailyInventoryRepository : JpaRepository<DailyInventory, DailyInventoryId> {

    /**
     * 날짜 **한 행**을 `SELECT ... FOR UPDATE` 로 잠근다.
     *
     * 여러 날짜를 `IN` 절 하나로 잠그지 않는다. 그러면 락 획득 순서가 플래너의
     * 손에 들어가고, 정렬을 지웠을 때 `T2` 가 실패한다는 보장이 사라진다 --
     * 플래너가 우연히 인덱스 순서로 돌려주면 데드락이 안 나고, **테스트가 통과하는데
     * 방어는 없는** 상태가 된다. 날짜 수만큼 왕복하는 비용을 내고 순서를
     * 호출부의 루프에 둔다 (ADR-0002 · 절대 규칙 2).
     *
     * 반환이 null 이면 그 날짜에 재고 격자가 없다는 뜻이다. 격자 없이 파는 것은
     * 차감 없이 파는 것이므로 거절 사유가 된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
        "select d from DailyInventory d " +
            "where d.id.roomTypeId = :roomTypeId and d.id.stayDate = :stayDate",
    )
    fun lockForUpdate(
        @Param("roomTypeId") roomTypeId: Long,
        @Param("stayDate") stayDate: LocalDate,
    ): DailyInventory?
}

interface ReservationRepository : JpaRepository<Reservation, Long> {

    /**
     * 조건부 상태 전이. **바뀐 행 수**를 준다.
     *
     * 이 한 문장이 셋을 동시에 준다 (`docs/01-domain-model.md` 재고 복원 알고리즘).
     *
     * | 얻는 것 | 이유 |
     * |---|---|
     * | 동시 취소 방어 | 두 번째 트랜잭션은 `rowcount` 0 -- 복원을 실행하지 않는다 |
     * | 취소 웹훅 멱등 | 이미 취소된 건에 2xx. 채널 재시도를 유발하지 않는다 (절대 규칙 5) |
     * | 락 순서 강제 | `rowcount` 를 봐야 다음 단계로 갈지 알 수 있으므로, 순서를 어기려면 코드를 뒤집어야 한다 (ADR-0011) |
     *
     * 세 번째가 특히 값을 한다. **규칙을 지키는 것이 아니라 어길 수 없는 형태다.**
     *
     * `SELECT FOR UPDATE` -> 검증 -> `UPDATE` 세 단계를 한 문장으로 접는다.
     * 나눠 쓰면 두 트랜잭션이 모두 `CONFIRMED` 를 읽고 둘 다 복원한다 --
     * 예약은 1건인데 `sold` 가 2 줄고, 그 자리에 새 예약이 들어오면 오버부킹이다.
     */
    @Modifying
    @Query(
        "update Reservation r set r.status = :to, r.updatedAt = :now " +
            "where r.id = :id and r.status = :from",
    )
    fun transition(
        @Param("id") id: Long,
        @Param("from") from: ReservationStatus,
        @Param("to") to: ReservationStatus,
        @Param("now") now: Instant,
    ): Int
}

interface InventoryHoldRepository : JpaRepository<InventoryHold, Long>

interface OutboxEventRepository : JpaRepository<OutboxEvent, Long>

interface InboundMessageRepository : JpaRepository<InboundMessage, Long>

interface ChannelPolicyRepository : JpaRepository<ChannelPolicy, ChannelPolicyId>
