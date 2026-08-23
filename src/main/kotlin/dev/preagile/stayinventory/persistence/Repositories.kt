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

    /**
     * 채널이 준 예약 번호로 찾는다. `UNIQUE(channel, channel_reservation_id)` 가
     * 있으므로 결과는 0 또는 1 이다.
     */
    fun findByChannelAndChannelReservationId(
        channel: String,
        channelReservationId: String,
    ): Reservation?
}

interface InventoryHoldRepository : JpaRepository<InventoryHold, Long>

interface OutboxEventRepository : JpaRepository<OutboxEvent, Long> {

    /**
     * 그 키의 다음 버전 번호.
     *
     * **`aggregate_type` 이 키의 한 축이다.** 재고 통보와 정책 통보는 같은
     * (룸타입, 날짜) 격자를 쓰지만 **서로를 낡게 만들지 않는다** -- 캡을 바꿨다고
     * 재고 통보가 건너뛰어지면 채널은 잔여를 영영 모른다 (ADR-0012 개정).
     *
     * **경합 방어가 이 쿼리 안에 없다.** 호출부가 그 키를 직렬화하는 행 락을
     * 이미 쥐고 있어야 한다.
     *
     * | 통보 | 직렬화 지점 |
     * |---|---|
     * | 재고 | `daily_inventory` 행 (`FOR UPDATE`) |
     * | 정책 | `channel_policy` 행 (UPSERT 가 잡는다) |
     *
     * 락 없이 부르면 두 트랜잭션이 같은 번호를 받아 간다. 그러면 릴레이의
     * `>` 비교가 둘 중 어느 것도 낡았다고 판정하지 않아 순서 방어가 통째로 사라진다.
     */
    @Query(
        value = "SELECT COALESCE(MAX(version), 0) + 1 FROM outbox_event " +
            "WHERE aggregate_type = :aggregateType " +
            "AND room_type_id = :roomTypeId AND stay_date = :stayDate",
        nativeQuery = true,
    )
    fun nextVersionFor(
        @Param("aggregateType") aggregateType: String,
        @Param("roomTypeId") roomTypeId: Long,
        @Param("stayDate") stayDate: LocalDate,
    ): Long
}

interface InboundMessageRepository : JpaRepository<InboundMessage, Long> {

    /**
     * 이미 받은 알림인지 본다. **`IS NOT DISTINCT FROM` 을 쓴다.**
     *
     * 파생 쿼리(`findByChannelAndExternalIdAndSequenceKey`)로 쓰면 `sequenceKey` 가
     * null 일 때 `= NULL` 이 되어 **아무 행에도 맞지 않는다.** 순서키를 주지 않는
     * 채널에서만 조기 반환이 조용히 죽고, 순서키를 주는 채널로 테스트하면 통과한다 --
     * 스키마의 `NULLS NOT DISTINCT` 가 막으려던 것과 **정확히 같은 함정**이다.
     *
     * 애플리케이션 판정과 DB 제약이 같은 것을 같다고 봐야 두 방어선이 겹친다.
     * 판정이 어긋나면 조기 반환은 통과시키고 DB 만 막아, 정상 경로가 늘 예외로 끝난다.
     *
     * **`event_type` 이 키에 있다.** 없으면 순서키를 주지 않는 채널에서 같은 예약의
     * 취소가 생성과 같은 키를 갖고, 중복으로 버려진다 -- 그리고 2xx 를 주므로
     * 채널은 전달됐다고 믿고 다시 보내지 않는다. **취소가 영구히 유실된다.**
     */
    @Query(
        value = "SELECT count(*) > 0 FROM inbound_message " +
            "WHERE channel = :channel AND external_id = :externalId " +
            "AND event_type IS NOT DISTINCT FROM :eventType " +
            "AND sequence_key IS NOT DISTINCT FROM :sequenceKey",
        nativeQuery = true,
    )
    fun alreadyReceived(
        @Param("channel") channel: String,
        @Param("externalId") externalId: String,
        @Param("eventType") eventType: String?,
        @Param("sequenceKey") sequenceKey: String?,
    ): Boolean

    /**
     * 처리 대기 알림을 꺼낸다.
     *
     * `(external_id, sequence_rank)` 순으로 준다. 같은 예약에 대한 알림은 순서
     * 순서로 처리해야 하고(`07-reconciliation.md`), 서로 다른 예약끼리는 순서를
     * 신경 쓰지 않는다.
     *
     * **`sequence_key` 가 아니라 `sequence_rank` 로 정렬한다** (ADR-0013).
     * 원본은 `VARCHAR` 이므로 문자열 정렬이 되어 숫자 리비전 `10` 이 `2` 보다
     * 먼저 처리됐다 -- 순서 역전을 막으려는 정렬이 역전을 만들었다.
     *
     * `NULLS FIRST` 는 정규화할 수 없는 알림을 먼저 보낸다 -- 그 채널은 어차피
     * 순서를 복원할 수 없으므로 도착 순서(`id`)가 최선이다.
     */
    @Query(
        value = "SELECT * FROM inbound_message WHERE status = 'PENDING' " +
            "ORDER BY external_id, sequence_rank NULLS FIRST, id LIMIT :limit",
        nativeQuery = true,
    )
    fun findPending(@Param("limit") limit: Int): List<InboundMessage>

    /**
     * 이 예약에 **더 높은 rank 의 취소가 이미 기록돼 있나** (ADR-0013 의 묘비).
     *
     * 정렬은 한 배치 안에서만 순서를 정한다. 취소와 생성이 다른 폴링 주기에
     * 도착하면 정렬이 개입할 자리가 없고, 그때 늦게 온 생성이 예약을 확정해
     * **최신 취소가 사라진다.**
     *
     * 별도 테이블을 만들지 않는 이유는 `inbound_message` 가 이미 묘비이기
     * 때문이다 -- 받은 사실을 보존하는 것이 Inbox 의 임무이고, 그 기록에
     * 필요한 정보가 다 있다.
     *
     * `rank` 가 `null` 인 알림은 세지 않는다. 비교할 수 없는 값으로 "더 높다" 를
     * 판정할 수 없고, 그 채널의 한계는 drift 검출이 받는다.
     */
    @Query(
        value = "SELECT count(*) > 0 FROM inbound_message " +
            "WHERE channel = :channel AND external_id = :externalId " +
            "AND sequence_rank IS NOT NULL AND sequence_rank > :rank " +
            "AND event_type = 'RESERVATION_CANCELED'",
        nativeQuery = true,
    )
    fun hasLaterCancel(
        @Param("channel") channel: String,
        @Param("externalId") externalId: String,
        @Param("rank") rank: Long,
    ): Boolean
}

interface ChannelPolicyRepository : JpaRepository<ChannelPolicy, ChannelPolicyId>
