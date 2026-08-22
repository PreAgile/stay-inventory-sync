package dev.preagile.stayinventory.outbox

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.channel.ChannelSyncResult
import dev.preagile.stayinventory.channel.RecordingChannelAdapter
import dev.preagile.stayinventory.inventory.InventoryFixture
import dev.preagile.stayinventory.inventory.InventoryService
import dev.preagile.stayinventory.inventory.ReserveCommand
import dev.preagile.stayinventory.inventory.ReserveResult
import dev.preagile.stayinventory.outbox.relay.OutboxRelay
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * **B4 — 키 단위 버전 스탬프. 순서 역전과 동일 키 병합.**
 *
 * ## 왜 Tier 2 인데 급한가
 *
 * `next_attempt_at`(백오프)이 존재하는 한 **단일 릴레이 인스턴스에서도 순서가
 * 뒤집힌다.** 인스턴스 수의 문제가 아니다.
 *
 * ```
 * t1  (룸타입A, 3/15) 예약 → 3→2   #1 생성, 발행 실패 → +1분 뒤 재시도
 * t2  (룸타입A, 3/15) 취소 → 2→3   #2 즉시 발행 성공 → 채널 잔여 3  ✓
 * t3  #1 재시도 성공               → 채널 잔여 2  ✗  실제는 3
 * ```
 *
 * 결과는 기회손실이고, 반대 방향이면 **오버부킹** -- 이 저장소가 막겠다고 선언한
 * 바로 그 실패다. 외부 요구와도 맞다. 순서 보장은 연동사 책임으로 규정되며
 * **수신 측이 정렬해 주지 않는다.**
 *
 * ## 단조성을 무엇이 보장하는가
 *
 * 시퀀스가 아니라 **`daily_inventory` 행 락**이다. 같은 키의 통보를 만드는 모든
 * 트랜잭션이 그 행을 잠그고 지나가므로, `MAX(version) + 1` 을 읽고 쓰는 사이에
 * 다른 트랜잭션이 끼어들 수 없다. **재고 카운터를 직렬화하는 락이 버전도 함께
 * 직렬화한다** -- 새 동시성 장치를 들이지 않았다.
 */
@SpringBootTest
@Import(PostgresTestContainer::class)
class OutboxVersionStampTest(
    private val dataSource: DataSource,
    private val jdbc: JdbcTemplate,
    private val relay: OutboxRelay,
    private val adapter: RecordingChannelAdapter,
    private val inventoryService: InventoryService,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)
    val fixture = InventoryFixture(dataSource)

    beforeTest {
        fixture.wipe()
        adapter.reset()
    }

    fun reserve(roomTypeId: Long, nights: Long = 1): Long =
        inventoryService.reserve(
            ReserveCommand(
                roomTypeId = roomTypeId,
                checkIn = march1,
                checkOut = march1.plusDays(nights),
                roomCount = 1,
                channel = "CHANNEL_A",
                channelReservationId = UUID.randomUUID().toString(),
                guestName = "김손님",
            ),
        ).shouldBeInstanceOf<ReserveResult.Reserved>().reservationId

    fun versions(): List<Long> =
        jdbc.queryForList("SELECT version FROM outbox_event ORDER BY id", Long::class.java)

    fun statuses(): List<String> =
        jdbc.queryForList("SELECT status FROM outbox_event ORDER BY id", String::class.java)

    // ── 버전 생성 ─────────────────────────────────────────────────────────
    test("같은 키의 통보는 버전이 1부터 단조 증가한다") {
        // Given / When: 같은 (룸타입, 날짜) 를 세 번 건드린다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        val first = reserve(roomTypeId)
        reserve(roomTypeId)
        inventoryService.cancel(first)

        // Then
        versions() shouldContainExactly listOf(1L, 2L, 3L)
    }

    test("키가 다르면 버전이 서로 독립이다 — 전역 순서를 만들지 않는다") {
        // Given: 2박 예약. 3/1 과 3/2 는 다른 키다
        val roomTypeId = fixture.seedGrid(march1, days = 3, physicalTotal = 10)
        reserve(roomTypeId, nights = 2)
        reserve(roomTypeId, nights = 2)

        // Then: 각 키에서 1, 2 다. 전역 시퀀스면 1,2,3,4 가 되고
        // 그 시퀀스가 곧 모든 키를 묶는 직렬화 지점이 된다
        versions() shouldContainExactly listOf(1L, 1L, 2L, 2L)
    }

    test("키가 없는 이벤트는 셋 다 없다 — 일부만 있는 상태는 DB 가 막는다") {
        // Given / When: 키 일부만 넣으려 한다
        val rejected = runCatching {
            jdbc.update(
                """
                INSERT INTO outbox_event
                       (aggregate_type, aggregate_id, event_type, payload, room_type_id)
                VALUES ('X', 1, 'Y', '{}'::jsonb, 1)
                """.trimIndent(),
            )
        }.isFailure

        // Then: 릴레이가 "키는 있는데 버전이 없다" 를 만나면 판정을 포기하거나
        // NULL 비교로 조용히 통과시킨다. 그 상태를 만들 수 없게 한다
        rejected shouldBe true
    }

    // ── B4 본체 ───────────────────────────────────────────────────────────
    test("B4 — 백오프에 빠진 낡은 통보가 재시도에서 채널 상태를 되돌리지 않는다") {
        // Given: 예약으로 통보 #1(잔여 9)이 생겼는데 발행이 실패한다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        val reservationId = reserve(roomTypeId)
        val t0 = Instant.now()

        adapter.forcedResult = ChannelSyncResult.Retryable("502")
        relay.drain(now = t0).retried shouldBe 1
        adapter.reset()

        // 취소로 통보 #2(잔여 10)가 생기고, 이쪽은 바로 성공한다.
        //
        // 여기서 t0 를 쓰지 않는다. #2 의 next_attempt_at 은 취소 시점이라
        // t0 보다 뒤이고, t0 기준으로 폴링하면 아직 안 잡힌다 -- 시각을 고정한
        // 테스트에서 흔한 함정이다
        inventoryService.cancel(reservationId)
        relay.drain().published shouldBe 1
        adapter.applied shouldBe 1

        // When: 백오프가 끝나 #1 이 다시 잡힌다
        val report = relay.drain(now = t0.plus(Duration.ofMinutes(2)))

        // Then: #1 은 나가지 않는다. 나갔다면 채널 잔여가 10 에서 9 로 되돌아간다
        report.published shouldBe 0
        report.superseded shouldBe 1
        adapter.applied shouldBe 1

        statuses() shouldContainExactly listOf("SUPERSEDED", "PUBLISHED")
    }

    test("반대 방향이면 오버부킹이다 — 그래서 이 방어가 Tier 2 보다 급하다") {
        // Given: 취소가 먼저 백오프에 빠지고 예약이 나중에 성공하는 경우.
        // 낡은 통보(잔여 10)가 뒤늦게 나가면 채널은 방이 있다고 믿는다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 1)
        val first = reserve(roomTypeId)
        relay.drain()
        adapter.reset()

        inventoryService.cancel(first) // #2 잔여 1
        // 이벤트가 만들어진 **뒤에** 기준 시각을 잡는다. 먼저 잡으면 그 이벤트의
        // next_attempt_at 이 기준보다 뒤라서 폴링에 걸리지 않는다
        val t0 = Instant.now()
        adapter.forcedResult = ChannelSyncResult.Retryable("502")
        relay.drain(now = t0).retried shouldBe 1
        adapter.reset()

        reserve(roomTypeId) // #3 잔여 0
        relay.drain().published shouldBe 1

        // When: 백오프가 끝나 #2(잔여 1)가 다시 잡힌다
        relay.drain(now = t0.plus(Duration.ofMinutes(2))).superseded shouldBe 1

        // Then: 마지막으로 채널에 간 값이 잔여 0 이어야 한다.
        // #2 가 나갔다면 채널은 남은 방이 있다고 믿고 팔았을 것이다
        val lastPublished = jdbc.queryForObject(
            "SELECT payload::text FROM outbox_event WHERE status = 'PUBLISHED' " +
                "ORDER BY version DESC LIMIT 1",
            String::class.java,
        )!!
        lastPublished shouldContain "\"remaining\": 0"
    }

    // ── 배치 내 병합 ──────────────────────────────────────────────────────
    test("한 배치에 같은 키가 여러 개면 가장 새 것만 나간다 — 레이트 리밋을 아낀다") {
        // Given: 같은 날짜에 예약·예약·취소가 연달아 일어나 통보가 3건
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        val first = reserve(roomTypeId)
        reserve(roomTypeId)
        inventoryService.cancel(first)

        // When: 한 번에 집는다
        val report = relay.drain(limit = 10)

        // Then: 채널 호출은 1회다. 낡은 둘을 보내 봐야 바로 덮어써지고
        // 분당 20건 상한만 태운다
        adapter.attempts shouldBe 1
        report.published shouldBe 1
        report.superseded shouldBe 2
        statuses() shouldContainExactly listOf("SUPERSEDED", "SUPERSEDED", "PUBLISHED")
    }

    test("서로 다른 키는 병합되지 않는다 — 각 날짜가 자기 값을 받는다") {
        // Given: 3박 예약 -> 서로 다른 키 3개
        val roomTypeId = fixture.seedGrid(march1, days = 4, physicalTotal = 10)
        reserve(roomTypeId, nights = 3)

        // When
        val report = relay.drain()

        // Then: 키가 다르면 순서를 비교할 이유가 없다.
        // 여기서 병합되면 3/2·3/3 의 재고 변경이 채널에 영영 안 간다
        report.published shouldBe 3
        report.superseded shouldBe 0
    }

    // ── 상태의 의미 ───────────────────────────────────────────────────────
    test("건너뛴 통보는 PUBLISHED 가 아니다 — 발행량을 부풀리지 않는다") {
        // Given / When
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        val first = reserve(roomTypeId)
        inventoryService.cancel(first)
        relay.drain()

        // Then: PUBLISHED 로 적으면 "왜 채널에 안 갔는가" 를 되짚을 수 없고,
        // 지표(#10)에서 발행량이 실제보다 크게 나온다
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE status = 'PUBLISHED'", Int::class.java,
        ) shouldBe 1
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE status = 'SUPERSEDED'", Int::class.java,
        ) shouldBe 1
        // 건너뛴 것은 발행 시각도 없다
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE status = 'SUPERSEDED' AND published_at IS NOT NULL",
            Int::class.java,
        ) shouldBe 0
    }

    test("아직 나가지 않은 새 이벤트 때문에 지금 나갈 이벤트가 건너뛰어지지 않는다") {
        // Given: #1 은 이 배치에, #2 는 백오프로 아직 대기 중이 아니라
        // **아예 다음 배치**에 있다고 하자. limit 1 로 그 상황을 만든다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        val first = reserve(roomTypeId)
        inventoryService.cancel(first)

        // When: 한 건씩만 집는다
        val firstReport = relay.drain(limit = 1)

        // Then: #1 은 나가야 한다. PENDING 인 #2 까지 세어 낡음을 판정하면
        // 아직 나가지 않은 값 때문에 지금 나가야 할 값이 사라진다
        firstReport.published shouldBe 1
        firstReport.superseded shouldBe 0

        // 그 다음 배치에서 #2 가 나가면서 #1 을 덮는다
        relay.drain(limit = 1).published shouldBe 1
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
