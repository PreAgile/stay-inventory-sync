package dev.preagile.stayinventory.persistence

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
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

interface ReservationRepository : JpaRepository<Reservation, Long>

interface InventoryHoldRepository : JpaRepository<InventoryHold, Long>

interface OutboxEventRepository : JpaRepository<OutboxEvent, Long>

interface InboundMessageRepository : JpaRepository<InboundMessage, Long>

interface ChannelPolicyRepository : JpaRepository<ChannelPolicy, ChannelPolicyId>
