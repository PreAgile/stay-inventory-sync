package dev.preagile.stayinventory.resync

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.channel.ChannelSyncResult
import dev.preagile.stayinventory.channel.RecordingChannelAdapter
import dev.preagile.stayinventory.inventory.InventoryFixture
import dev.preagile.stayinventory.inventory.InventoryService
import dev.preagile.stayinventory.inventory.ReserveCommand
import dev.preagile.stayinventory.inventory.ReserveResult
import dev.preagile.stayinventory.outbox.relay.OutboxRelay
import dev.preagile.stayinventory.policy.ChannelPolicyService
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * **B6 — 정기 재동기화. `B4` 가 닫지 못하는 창의 최종 방어선.**
 *
 * ## 무엇을 고치는가
 *
 * `ADR-0012` 의 버전 스탬프는 `PUBLISHED` 마킹을 기준으로 낡음을 판별하는데,
 * **마킹은 외부 호출이 성공한 뒤에** 일어난다.
 *
 * ```
 * 외부 호출 성공 ──▶ (릴레이 종료) ──▶ PUBLISHED 마킹 없음
 *                                       낡은 재시도가 skip 검사를 통과한다
 * ```
 *
 * `T4` 가 다루는 창과 같은 창인데 **결과의 성격이 다르다** — 중복 발행은 멱등하게
 * 흡수되지만 **낡은 값 재전송은 흡수되지 않는다.**
 *
 * 창을 0 으로 만드는 대신 **틀린 상태가 남더라도 유한 시간 안에 스스로
 * 고쳐지게** 만든다. 순서 문제의 최종 방어선은 순서가 아니라 재동기화다.
 *
 * ## 이 스펙이 지키는 요건 하나
 *
 * **최종 방어선이 스스로 정합성을 깰 수 없어야 한다.** 재동기화는 읽고 보낼 뿐
 * 아무것도 쓰지 않는다 — 그것이 이 방어선을 믿을 수 있게 만드는 유일한 근거다.
 */
@SpringBootTest
@Import(PostgresTestContainer::class)
class InventoryResyncTest(
    private val dataSource: DataSource,
    private val jdbc: JdbcTemplate,
    private val relay: OutboxRelay,
    private val adapter: RecordingChannelAdapter,
    private val inventoryService: InventoryService,
    private val policies: ChannelPolicyService,
    private val resync: InventoryResyncService,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)
    val fixture = InventoryFixture(dataSource)
    val window = march1 to march1.plusDays(10)

    beforeTest {
        fixture.wipe()
        adapter.reset()
    }

    fun reserve(roomTypeId: Long, roomCount: Int = 1) =
        inventoryService.reserve(
            ReserveCommand(
                roomTypeId = roomTypeId,
                checkIn = march1,
                checkOut = march1.plusDays(1),
                roomCount = roomCount,
                channel = "CHANNEL_A",
                channelReservationId = UUID.randomUUID().toString(),
                guestName = "김손님",
            ),
        ).shouldBeInstanceOf<ReserveResult.Reserved>()

    fun runResync(limit: Int = InventoryResyncService.DEFAULT_LIMIT) =
        resync.resync(window.first, window.second, limit)

    // ── 창을 닫는다 ───────────────────────────────────────────────────────
    test("B6 — 새 통보의 마킹이 유실되면 낡은 통보가 그 위를 덮는다. 재동기화가 되돌린다") {
        // Given: 발행-마킹 창을 정확히 재현한다.
        //
        // 창이 열리는 쪽은 **새 통보**다. 낡은 통보의 마킹이 유실되는 경우는
        // B4 가 막는다 -- 더 새로운 PUBLISHED 가 있으므로 skip 된다.
        // 문제는 반대다: 새 통보가 채널에 도달했는데 마킹을 못 남기면,
        // 그 통보는 PENDING 으로 남고 **낡은 통보의 skip 검사가 통과한다.**
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        val reservation = reserve(roomTypeId)          // #1 잔여 9 (v1)
        inventoryService.cancel(reservation.reservationId) // #2 잔여 10 (v2)

        val events = relay.claimPending(10).filter { it.stayDate == march1 }.sortedBy { it.version }
        events.size shouldBe 2

        // 새 통보가 채널에 도달했다. 마킹 직전에 릴레이가 죽는다
        adapter.push(events[1].id, events[1].payload)
        adapter.currentInventory(roomTypeId, march1, march1.plusDays(1))[march1] shouldBe 10

        // When: 임대가 만료되고 낡은 통보가 혼자 잡힌다.
        //
        // limit 1 로 집어 배치 내 병합을 피한다 -- 한 배치에 둘이 들어오면
        // 그때는 B4 가 막아 준다. 창이 열리는 것은 따로 잡힐 때다
        val afterLease = java.time.Instant.now().plusSeconds(300)
        relay.drain(limit = 1, now = afterLease).published shouldBe 1

        // Then: 낡은 값이 새 값을 덮었다. **B4 가 이것을 막지 못한다** --
        // 더 큰 버전이 PUBLISHED 로 기록된 적이 없기 때문이다
        adapter.currentInventory(roomTypeId, march1, march1.plusDays(1))[march1] shouldBe 9

        // When: 재동기화가 돈다
        val report = runResync()

        // Then: 내부 진실로 수렴한다. 순서를 맞추려 든 것이 아니라
        // **틀린 상태를 유한 시간 안에 고친 것**이다
        report.sent shouldBe 3
        adapter.currentInventory(roomTypeId, march1, march1.plusDays(1))[march1] shouldBe 10
    }

    test("같은 값을 두 번 보내도 흡수되지 않는다 — 멱등키를 받지 않는 이유") {
        // Given: 정상 상태
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId)
        relay.drain()

        // When: 재동기화를 두 번 돌린다
        runResync()
        runResync()

        // Then: 두 번 다 적용된다. 멱등키를 붙이면 두 번째가 흡수되어
        // 아무 일도 하지 않고, **그 사실이 조용하다** -- 재동기화가 돌고 있다는
        // 신호는 나오는데 실제로는 아무것도 고치지 않는 상태가 된다
        adapter.snapshots shouldBe 4
    }

    test("채널이 아예 모르던 날짜도 채워진다") {
        // Given: 격자만 있고 통보가 나간 적 없다 (#6 이 잡는 그 상태)
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        adapter.currentInventory(roomTypeId, window.first, window.second).size shouldBe 0

        // When
        runResync()

        // Then: 초기 동기화가 빠진 경우도 이 경로가 메운다
        adapter.currentInventory(roomTypeId, window.first, window.second).size shouldBe 3
    }

    // ── 아무것도 쓰지 않는다 ──────────────────────────────────────────────
    test("재동기화는 재고를 바꾸지 않는다 — 최종 방어선이 정합성을 깰 수 없어야 한다") {
        // Given
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        reserve(roomTypeId, roomCount = 3)
        val before = jdbc.queryForMap(
            "SELECT physical_total, overbooking_limit, sold FROM daily_inventory " +
                "WHERE room_type_id = ? AND stay_date = ?",
            roomTypeId, march1,
        )

        // When
        runResync()

        // Then: 읽고 보낼 뿐이다. 이 경로가 쓰기를 하면 최종 방어선 자체가
        // 정합성 사고의 원인이 될 수 있고, 그러면 믿을 수 없다
        jdbc.queryForMap(
            "SELECT physical_total, overbooking_limit, sold FROM daily_inventory " +
                "WHERE room_type_id = ? AND stay_date = ?",
            roomTypeId, march1,
        ) shouldBe before
    }

    test("이벤트 큐를 경유하지 않는다") {
        // Given: 발행 대기 통보가 있는 상태
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        reserve(roomTypeId)
        val outboxBefore = jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event", Int::class.java,
        )

        // When
        runResync()

        // Then: Outbox 를 타면 이 통보도 버전 판정을 받는데, 재동기화의 값은
        // 언제나 최신이므로 판정 대상이 아니다. 그리고 큐를 타면 밀려 있던
        // 낡은 이벤트 뒤에 줄을 서게 되어 정작 고치려던 상태를 늦게 고친다
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event", Int::class.java,
        ) shouldBe outboxBefore
    }

    test("정책은 건드리지 않는다 — 다른 축이다") {
        // Given: 캡이 걸려 있다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        policies.setCap(roomTypeId, march1, "CHANNEL_C", value = 3)
        relay.drain()
        reserve(roomTypeId)

        // When
        runResync()

        // Then: 재고 축만 수렴시킨다. 정책까지 재전송하면 현장이 채널에서
        // 조정한 것을 매 주기 되돌리게 되고, 그것이 ADR-0009 가 기각한
        // "항상 우리 값으로 덮어쓰기" 다
        adapter.capFor("CHANNEL_C", roomTypeId, march1) shouldBe 3
        adapter.exposedFor("CHANNEL_C", roomTypeId, march1) shouldBe 3
    }

    // ── 범위와 상한 ───────────────────────────────────────────────────────
    test("구간 밖의 격자는 보내지 않는다") {
        // Given: 3/1 부터 5일치 격자
        fixture.seedGrid(march1, days = 5, physicalTotal = 10)

        // When: 이틀만 재동기화한다
        val report = resync.resync(march1, march1.plusDays(2))

        // Then: 반개구간이다
        report.sent shouldBe 2
    }

    test("상한에서 잘리면 그 사실을 보고에 담는다") {
        // Given: 격자 5일치
        fixture.seedGrid(march1, days = 5, physicalTotal = 10)

        // When: 상한 2
        val report = runResync(limit = 2)

        // Then: 조용히 자르면 "재동기화했다" 는 신호가 거짓이 된다.
        // 절반만 보내고도 성공으로 읽힌다
        report.sent shouldBe 2
        report.truncated shouldBe true
    }

    test("상한에 딱 맞으면 잘리지 않았다고 보고한다") {
        // Given / When
        fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        val report = runResync(limit = 3)

        // Then: 경계에서 truncated 가 참이 되면 매 주기 거짓 경보가 뜬다
        report.sent shouldBe 3
        report.truncated shouldBe false
    }

    test("구간이 뒤집혀 있으면 거부한다") {
        // Given / When / Then: 조용히 0건을 보내면 "보낼 것이 없었다" 로 읽힌다
        val thrown = runCatching {
            resync.resync(march1.plusDays(3), march1)
        }.exceptionOrNull()

        (thrown is IllegalArgumentException) shouldBe true
    }

    // ── 실패 ──────────────────────────────────────────────────────────────
    test("전송 실패는 재시도하지 않고 다음 주기에 맡긴다") {
        // Given: 채널이 실패를 준다
        fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        adapter.forcedResult = ChannelSyncResult.Retryable("502")

        // When
        val report = runResync()

        // Then: 여기서 재시도 큐를 만들면 재동기화가 또 하나의 발행 경로가 되고,
        // 그 경로에 순서 문제가 생긴다. 최종 방어선은 단순해야 한다 --
        // 다음 주기가 어차피 같은 값을 다시 보낸다
        report.sent shouldBe 0
        report.failed shouldBe 3
    }

    test("어댑터가 예외를 던져도 나머지를 계속 보낸다") {
        // Given: 격자 3일치
        fixture.seedGrid(march1, days = 3, physicalTotal = 10)

        // When / Then: 한 건의 실패가 나머지를 막으면 어긋난 격자 하나 때문에
        // 전체 수렴이 멈춘다
        val report = runResync()
        report.sent shouldBe 3
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
