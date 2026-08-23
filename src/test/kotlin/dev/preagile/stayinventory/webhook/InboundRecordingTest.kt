package dev.preagile.stayinventory.webhook

import dev.preagile.stayinventory.PostgresTestContainer
import dev.preagile.stayinventory.domain.InboundStatus
import dev.preagile.stayinventory.inventory.InventoryFixture
import dev.preagile.stayinventory.persistence.InboundMessageRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.LocalDate
import javax.sql.DataSource

/**
 * 수신 1단계 -- **적고, 2xx 를 주고, 끝낸다.**
 *
 * 여기서 도메인을 건드리지 않는 것이 Inbox 의 전부다 (절대 규칙 9). 처리하다
 * 느려지면 채널은 그것을 실패로 보고 같은 알림을 또 보내므로, 수신과 처리를
 * 나눠 채널의 재시도 시계와 우리 처리 시간을 분리한다.
 *
 * **이 스펙의 절반이 `sequenceKey` 가 null 인 경우다.** 그쪽이 조용히 뚫리는 쪽이다.
 */
@SpringBootTest
@Import(PostgresTestContainer::class)
class InboundRecordingTest(
    private val dataSource: DataSource,
    private val recorder: InboundMessageRecorder,
    private val inbound: InboundMessageRepository,
) : FunSpec({

    val march1 = LocalDate.of(2026, 3, 1)
    val fixture = InventoryFixture(dataSource)

    beforeTest { fixture.wipe() }

    fun webhook(
        externalId: String = "R-1001",
        sequenceKey: String? = null,
        event: WebhookEvent = WebhookEvent.RESERVATION_CREATED,
    ) = ReservationWebhook(
        event = event,
        channelReservationId = externalId,
        sequenceKey = sequenceKey,
        roomTypeId = 1,
        checkIn = march1,
        checkOut = march1.plusDays(2),
        roomCount = 1,
        guestName = "김손님",
    )

    test("처음 받은 알림은 적히고 PENDING 으로 남는다") {
        // Given / When
        val outcome = recorder.record("CHANNEL_A", webhook())

        // Then: 이 단계에서 예약을 만들지 않는다. 적기만 한다
        outcome.shouldBeInstanceOf<RecordOutcome.Accepted>()
        val saved = inbound.findAll().single()
        saved.status shouldBe InboundStatus.PENDING
        saved.processedAt shouldBe null
        saved.externalId shouldBe "R-1001"
    }

    test("순서키 없는 같은 알림을 두 번 받으면 두 번째는 중복이다") {
        // Given: 순서키를 주지 않는 채널
        recorder.record("CHANNEL_A", webhook(sequenceKey = null))

        // When: 같은 알림이 또 온다
        val second = recorder.record("CHANNEL_A", webhook(sequenceKey = null))

        // Then: 여기가 조용히 뚫리는 자리다. 파생 쿼리로 판정하면 sequenceKey 가
        // null 일 때 `= NULL` 이 되어 아무 행에도 맞지 않고, 조기 반환이 늘 실패해
        // 정상 경로가 매번 DB 예외로 끝난다
        second shouldBe RecordOutcome.Duplicate
        inbound.count() shouldBe 1
    }

    test("순서키가 null 이어도 조기 반환이 중복을 알아본다 — 결과가 아니라 쿼리를 본다") {
        // Given: 순서키 없는 알림 하나가 이미 적혀 있다
        recorder.record("CHANNEL_A", webhook(sequenceKey = null))

        // When: 판정 쿼리에 직접 묻는다
        val recognized = inbound.alreadyReceived("CHANNEL_A", "R-1001", "RESERVATION_CREATED", null)

        // Then / 왜 이렇게 보는가:
        //
        // 위 테스트(두 번 받으면 중복)는 이 쿼리를 `= :sequenceKey` 로 망가뜨려도
        // **통과한다.** 조기 반환이 죽으면 INSERT 가 DB UNIQUE 에 걸리고, 그
        // 예외를 잡아 똑같이 Duplicate 를 돌려주기 때문이다. 두 방어선이 같은
        // 결과를 내므로 결과만 봐서는 앞의 것이 살아 있는지 알 수 없다.
        //
        // 그래서 관측 가능한 결과가 아니라 **판정 자체**를 본다. 조기 반환이
        // 죽으면 정상 경로가 매번 DB 예외로 끝나고, 그것은 성능 문제가 아니라
        // "정상 동작이 예외로 보고되는" 상태다 -- 진짜 이상이 그 노이즈에 묻힌다.
        recognized shouldBe true

        // 순서키를 주는 채널에서는 어느 쪽으로 짜도 맞으므로 조용히 뚫린다.
        // 그래서 null 쪽을 먼저 본다
        inbound.alreadyReceived("CHANNEL_A", "R-1001", "RESERVATION_CREATED", "1") shouldBe false
        inbound.alreadyReceived("CHANNEL_B", "R-1001", "RESERVATION_CREATED", null) shouldBe false
    }

    test("순서키가 다르면 다른 알림이다 — 같은 예약의 후속 변경이다") {
        // Given / When: 같은 예약, 다른 리비전
        recorder.record("CHANNEL_A", webhook(sequenceKey = "1"))
        recorder.record("CHANNEL_A", webhook(sequenceKey = "2"))

        // Then: 둘 다 적힌다. 순서키가 있는 채널은 같은 예약의 변경 이력을
        // 순서대로 처리할 수 있다
        inbound.count() shouldBe 2
    }

    test("채널이 다르면 예약 번호가 같아도 다른 알림이다") {
        // Given / When
        recorder.record("CHANNEL_A", webhook())
        recorder.record("CHANNEL_B", webhook())

        // Then: 예약 번호는 채널 안에서만 유일하다. 채널을 키에서 빼면
        // 다른 채널의 정상 예약이 중복으로 버려진다
        inbound.count() shouldBe 2
    }

    test("취소 알림은 생성 알림과 다른 알림이다") {
        // Given: 같은 예약의 생성과 취소. 순서키가 없는 채널이다
        recorder.record("CHANNEL_A", webhook(sequenceKey = null))

        // When
        val cancel = recorder.record(
            "CHANNEL_A",
            webhook(sequenceKey = "canceled", event = WebhookEvent.RESERVATION_CANCELED),
        )

        // Then: 순서키가 다르므로 흡수되지 않는다. 흡수되면 취소가 사라진다
        cancel.shouldBeInstanceOf<RecordOutcome.Accepted>()
        inbound.count() shouldBe 2
    }

    test("payload 는 받은 그대로 적힌다 — 해석에 실패해도 받은 사실은 남는다") {
        // Given / When: roomTypeId 가 빠진 알림 (처리 단계에서 실패할 것이다)
        recorder.record(
            "CHANNEL_A",
            ReservationWebhook(
                event = WebhookEvent.RESERVATION_CREATED,
                channelReservationId = "R-broken",
                sequenceKey = null,
            ),
        )

        // Then: 수신 단계는 해석하지 않는다. 해석해서 거절하면 그 알림은
        // 어디에도 남지 않고, 채널이 재전송을 멈추는 순간 영영 사라진다
        inbound.findAll().single().payload.contains("R-broken") shouldBe true
    }
}) {
    override fun extensions() = listOf(SpringExtension)
}
