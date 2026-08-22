package dev.preagile.stayinventory.persistence

import org.springframework.data.jpa.repository.JpaRepository

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

interface DailyInventoryRepository : JpaRepository<DailyInventory, DailyInventoryId>

interface ReservationRepository : JpaRepository<Reservation, Long>

interface InventoryHoldRepository : JpaRepository<InventoryHold, Long>

interface OutboxEventRepository : JpaRepository<OutboxEvent, Long>

interface InboundMessageRepository : JpaRepository<InboundMessage, Long>

interface ChannelPolicyRepository : JpaRepository<ChannelPolicy, ChannelPolicyId>
