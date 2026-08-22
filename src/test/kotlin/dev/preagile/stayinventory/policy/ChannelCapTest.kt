package dev.preagile.stayinventory.policy

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.channel.RecordingChannelAdapter
import dev.preagile.stayinventory.domain.ChannelPolicySource
import dev.preagile.stayinventory.inventory.InventoryFixture
import dev.preagile.stayinventory.inventory.InventoryService
import dev.preagile.stayinventory.inventory.ReserveCommand
import dev.preagile.stayinventory.inventory.ReserveResult
import dev.preagile.stayinventory.outbox.relay.OutboxRelay
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

/**
 * **B7 — 캡형 hybrid. `T11` · `T12`.**
 *
 * ## 이 슬라이스가 주장하는 것
 *
 * ```
 * 우리 DB     10                     카운터 하나. pooled 그대로
 * 채널 A · B  10                     규칙 없음
 * 채널 C       5                     max_availability = 5
 * ```
 *
 * **C 가 5 를 확보한 것이 아니다.** 실제 판매 가능량은 `min(캡, 잔여)` 이고
 * 그 `min` 을 계산하는 것은 **채널**이다 — 우리는 잔여와 규칙을 따로 보낸다.
 * 우리가 미리 곱해 보내면 채널마다 다른 재고를 관리하는 셈이 되고,
 * 그것이 `ADR-0001` 이 기각한 **배정**이다.
 *
 * **`T12` 가 핵심 증명이다.** 캡형이 재고 모델을 바꾸지 않는다는 주장을 검증한다 —
 * 바뀌면 캡형이 아니라 배정을 구현한 것이다.
 */
@SpringBootTest
@Import(PostgresTestContainer::class)
class ChannelCapTest(
    private val dataSource: DataSource,
    private val jdbc: JdbcTemplate,
    private val relay: OutboxRelay,
    private val adapter: RecordingChannelAdapter,
    private val inventoryService: InventoryService,
    private val policies: ChannelPolicyService,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)
    val fixture = InventoryFixture(dataSource)
    val channelC = "CHANNEL_C"

    beforeTest {
        fixture.wipe()
        adapter.reset()
    }

    fun reserve(roomTypeId: Long, roomCount: Int): ReserveResult =
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
        )

    fun grid(roomTypeId: Long) = jdbc.queryForMap(
        "SELECT physical_total, overbooking_limit, sold FROM daily_inventory " +
            "WHERE room_type_id = ? AND stay_date = ?",
        roomTypeId,
        march1,
    )

    // ── T11 ───────────────────────────────────────────────────────────────
    test("T11 — 캡이 걸린 채널의 노출값은 min(캡, 잔여) 다") {
        // Given: 전체 10, 채널 C 에 캡 5
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        policies.setCap(roomTypeId, march1, channelC, value = 5)
        relay.drain()
        adapter.capFor(channelC, roomTypeId, march1) shouldBe 5

        // When: A 손님이 6개 예약한다
        reserve(roomTypeId, roomCount = 6).shouldBeInstanceOf<ReserveResult.Reserved>()
        relay.drain()

        // Then: 잔여가 4 이므로 C 의 노출값은 5 가 아니라 4 다.
        //
        // 캡이 물량을 확보하는 것이었다면 C 는 여전히 5 를 보여 줬을 것이고,
        // 그 순간 남은 방 4 개에 5 개 요청이 들어올 수 있다
        adapter.exposedFor(channelC, roomTypeId, march1) shouldBe 4
    }

    test("T11 — 캡을 지워도 노출값은 잔여 그대로다. 재고는 캡과 무관하다") {
        // Given: 위와 같은 상태
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        policies.setCap(roomTypeId, march1, channelC, value = 5)
        reserve(roomTypeId, roomCount = 6)
        relay.drain()

        // When: 캡을 해제한다
        policies.removeCap(roomTypeId, march1, channelC) shouldBe true
        relay.drain()

        // Then: 4 그대로다. 캡은 상한이었을 뿐 재고를 만든 적이 없다
        adapter.capFor(channelC, roomTypeId, march1) shouldBe null
        adapter.exposedFor(channelC, roomTypeId, march1) shouldBe 4
    }

    test("캡이 잔여보다 크면 아무 일도 하지 않는다") {
        // Given: 전체 10, 캡 20
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        policies.setCap(roomTypeId, march1, channelC, value = 20)
        reserve(roomTypeId, roomCount = 1)
        relay.drain()

        // Then: min(20, 9) = 9. 캡이 재고를 늘릴 수는 없다
        adapter.exposedFor(channelC, roomTypeId, march1) shouldBe 9
    }

    test("캡 0 은 유효하다 — 그 채널에 안 팔겠다는 뜻이다") {
        // Given / When
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        policies.setCap(roomTypeId, march1, channelC, value = 0)
        reserve(roomTypeId, roomCount = 1)
        relay.drain()

        // Then: CLOSED 와 결과가 같다. 0 을 "값 없음" 으로 처리하면
        // "안 팔겠다" 를 표현할 방법이 사라진다
        adapter.exposedFor(channelC, roomTypeId, march1) shouldBe 0
    }

    test("캡이 없는 채널은 잔여를 그대로 본다") {
        // Given: C 에만 캡을 건다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        policies.setCap(roomTypeId, march1, channelC, value = 3)
        reserve(roomTypeId, roomCount = 1)
        relay.drain()

        // Then: A 는 9, C 는 3. 같은 재고를 다르게 보여 주는 것이 캡형이다
        adapter.exposedFor("CHANNEL_A", roomTypeId, march1) shouldBe 9
        adapter.exposedFor(channelC, roomTypeId, march1) shouldBe 3
    }

    // ── T12 — 이 이슈의 핵심 증명 ─────────────────────────────────────────
    test("T12 — 캡을 걸고 지워도 재고 모델이 하나도 바뀌지 않는다") {
        // Given: 전체 10, sold 3
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId, roomCount = 3)
        val before = grid(roomTypeId)
        before["sold"] shouldBe 3

        // When: 캡 2 를 걸고 지운다
        policies.setCap(roomTypeId, march1, channelC, value = 2)
        policies.removeCap(roomTypeId, march1, channelC)

        // Then: 세 값이 전부 그대로다.
        //
        // 하나라도 바뀌면 캡형이 아니라 배정을 구현한 것이고, 그때는
        // "남은 방이 있는데 매진이 뜨는" 상황이 만들어진다 (ADR-0001 기각 사유)
        grid(roomTypeId) shouldBe before

        // 불변식이 늘지 않았다는 것은 공용 훅이 매 테스트마다 확인한다
    }

    test("T12 — 캡은 예약 가능 여부를 바꾸지 않는다") {
        // Given: 전체 5 인데 채널 C 에 캡 1
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 5)
        policies.setCap(roomTypeId, march1, channelC, value = 1)

        // When: 5개를 예약한다
        val result = reserve(roomTypeId, roomCount = 5)

        // Then: 통과한다. 캡은 **노출** 상한이지 판매 상한이 아니다.
        // 캡이 차감 검사식에 끼어들면 그 순간 채널별 재고가 된다
        result.shouldBeInstanceOf<ReserveResult.Reserved>()
        grid(roomTypeId)["sold"] shouldBe 5
    }

    // ── 장부와 투영 ───────────────────────────────────────────────────────
    test("장부 변경과 통보가 같은 트랜잭션이다") {
        // Given / When
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        policies.setCap(roomTypeId, march1, channelC, value = 5)

        // Then: 장부만 바뀌고 채널에 안 나가면 "우리는 5 로 알고 채널은 10 을
        // 보여 주는" 상태가 되고, 그 차이는 diff 리포트(#6)에도 안 잡힌다 --
        // 그쪽은 재고를 본다
        jdbc.queryForObject(
            "SELECT count(*) FROM channel_policy WHERE kind = 'CAP'", Int::class.java,
        ) shouldBe 1
        jdbc.queryForObject(
            "SELECT count(*) FROM outbox_event WHERE aggregate_type = 'CHANNEL_POLICY'",
            Int::class.java,
        ) shouldBe 1
    }

    test("장부에는 source 가 OURS 로 남는다") {
        // Given / When
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        policies.setCap(roomTypeId, march1, channelC, value = 5)

        // Then: CHANNEL 은 인바운드 흡수 경로가 쓰는 값이다. 섞으면
        // "이 값을 우리가 정했나 현장이 정했나" 를 나중에 물을 수 없다
        val view = policies.list(
            jdbc.queryForObject("SELECT max(id) FROM property", Long::class.java)!!,
            march1,
            march1.plusDays(2),
        ).single()
        view.source shouldBe ChannelPolicySource.OURS
        view.value shouldBe 5
    }

    test("같은 캡을 두 번 넣어도 장부에는 한 행이다") {
        // Given / When
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        policies.setCap(roomTypeId, march1, channelC, value = 5)
        policies.setCap(roomTypeId, march1, channelC, value = 3)

        // Then: PK 가 (룸타입, 날짜, 채널, kind) 이므로 upsert 다
        jdbc.queryForObject(
            "SELECT count(*) FROM channel_policy", Int::class.java,
        ) shouldBe 1
        jdbc.queryForObject(
            "SELECT value FROM channel_policy", Int::class.java,
        ) shouldBe 3
    }

    test("없던 캡을 지우면 통보를 만들지 않는다") {
        // Given: 아무 캡도 없다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)

        // When
        val removed = policies.removeCap(roomTypeId, march1, channelC)

        // Then: 통보를 만들면 채널이 "해제하라" 를 반복해서 받고
        // 레이트 리밋만 태운다
        removed shouldBe false
        jdbc.queryForObject("SELECT count(*) FROM outbox_event", Int::class.java) shouldBe 0
    }

    // ── 두 레인 ───────────────────────────────────────────────────────────
    test("정책 통보가 재고 통보를 낡게 만들지 않는다 — 레인이 다르다") {
        // Given: 같은 (룸타입, 날짜) 에 재고 통보와 정책 통보가 함께 대기한다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        reserve(roomTypeId, roomCount = 1)
        policies.setCap(roomTypeId, march1, channelC, value = 5)

        // When: 한 배치로 집는다
        val report = relay.drain(limit = 10)

        // Then: 둘 다 나가야 한다.
        //
        // aggregate_type 을 키에서 빼면 정책 통보(버전 1)와 재고 통보(버전 1)가
        // 같은 키로 묶여 한쪽이 건너뛰어진다. 캡을 바꿨다고 재고 통보가 사라지면
        // 채널은 잔여를 영영 모른다
        report.published shouldBe 2
        report.superseded shouldBe 0
        adapter.capFor(channelC, roomTypeId, march1) shouldBe 5
        adapter.exposedFor(channelC, roomTypeId, march1) shouldBe 5
    }

    test("같은 레인 안에서는 낡은 정책 통보가 건너뛰어진다") {
        // Given: 캡을 세 번 바꾼다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        policies.setCap(roomTypeId, march1, channelC, value = 5)
        policies.setCap(roomTypeId, march1, channelC, value = 3)
        policies.setCap(roomTypeId, march1, channelC, value = 1)

        // When: 한 배치로 집는다
        val report = relay.drain(limit = 10)

        // Then: 마지막 것만 나간다. 순서가 뒤집히면 채널에 5 가 마지막으로
        // 남을 수 있고, 그것은 우리 장부와 어긋난 상태다
        report.published shouldBe 1
        report.superseded shouldBe 2
        adapter.capFor(channelC, roomTypeId, march1) shouldBe 1
    }

    test("정책 통보도 발행 시점에 장부를 다시 조회하지 않는다") {
        // Given: 캡 5 를 걸고 **발행한 뒤** 3 으로 바꾼다
        val roomTypeId = fixture.seedGrid(march1, days = 2, physicalTotal = 10)
        policies.setCap(roomTypeId, march1, channelC, value = 5)
        relay.drain()
        adapter.capFor(channelC, roomTypeId, march1) shouldBe 5

        policies.setCap(roomTypeId, march1, channelC, value = 3)
        relay.drain()

        // Then: 두 통보가 각각 5 와 3 을 실어 나갔다. 발행 시점에 장부를 읽으면
        // 첫 통보도 3 으로 나가고, 그것은 사건 기록이 아니라 최신값 전송이다
        adapter.capFor(channelC, roomTypeId, march1) shouldBe 3
        jdbc.queryForList(
            "SELECT payload::text FROM outbox_event WHERE aggregate_type = 'CHANNEL_POLICY' ORDER BY id",
            String::class.java,
        ).let { payloads ->
            payloads shouldHaveSize 2
            payloads[0].contains("\"value\": 5") shouldBe true
            payloads[1].contains("\"value\": 3") shouldBe true
        }
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
