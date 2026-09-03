# 코드 지도: 무엇이 어디에 있고, 무엇이 그것을 붙잡는가

[`09-how-to-verify.md`](09-how-to-verify.md) 는 **주장마다 어떤 명령으로 확인하나**를 모았습니다.
이 문서는 그 앞 단계입니다. **주장을 실제로 구현한 코드가 어느 파일의 어느 함수인지**,
그리고 **그것을 붙잡는 테스트가 어느 파일인지**를 한 표에 놓습니다.

처음 읽는 사람이 저장소에서 가장 자주 막히는 질문이 이것입니다.
"이 문서가 말하는 락 순서가 코드로는 어디 있나." 그 왕복을 없애는 것이 목적입니다.

파일 경로는 적고 **줄 번호는 적지 않습니다.** 줄 번호는 다음 커밋에 틀려지므로,
지도가 코드보다 낡는 순간 지도가 아니라 오답이 됩니다. 함수 이름으로 찾으십시오.

---

## 0. 한 요청을 따라가며 읽는 순서

예약 한 건이 들어와 채널 통보까지 나가는 경로입니다. 아래 여덟 파일이 그 경로 전부이고,
이 순서로 읽으면 저장소의 골격이 잡힙니다. 앞의 다섯이 트랜잭션 안, 뒤의 셋이 커밋 뒤입니다.

```
POST /reservations
  └─ api/ReservationController.kt            입력 검증과 멱등 키 수령
      └─ inventory/InventoryService.kt        reserve()  ← 트랜잭션 경계가 여기다
          ├─ persistence/Repositories.kt      lockForUpdate()  날짜 한 행씩 FOR UPDATE
          ├─ persistence/Reservation.kt       stayDates()      오름차순 생성
          └─ persistence/OutboxEvent.kt       같은 커밋에 통보를 적는다
      COMMIT
  ── 여기서 트랜잭션이 끝난다. 아래는 별도 경로다 ──
  outbox/relay/OutboxRelayScheduler.kt        주기 실행
      └─ outbox/relay/OutboxRelay.kt          drain() → claimPending() → 발행 → markPublished()
          └─ channel/ChannelAdapter.kt        멱등키를 받는 인터페이스
```

**트랜잭션 안에서 외부를 부르지 않는다**는 이 저장소의 규칙은 위 그림에서
`COMMIT` 아래로 릴레이가 내려가 있는 것으로 표현됩니다. 코드에서 그 경계는
`InventoryService.reserve()` 의 `@Transactional` 하나입니다.

---

## 1. 기능별 구현 위치와 그것을 붙잡는 테스트

| 기능 | 구현 (파일 · 함수) | 스키마 | 검증 테스트 |
|---|---|---|---|
| 다일 재고 원자적 차감 | `inventory/InventoryService.kt` `reserve()` | `V1__init.sql` | `inventory/InventoryDeductionTest.kt` |
| 날짜 한 행씩 잠그기 | `persistence/Repositories.kt` `DailyInventoryRepository.lockForUpdate()` | | `inventory/InventoryConcurrencyTest.kt` (`T1` `T2`) |
| 락 순서를 생성으로 보장 | `persistence/Reservation.kt` · `inventory/ReserveContract.kt` `stayDates()` | | `inventory/InventoryConcurrencyTest.kt` 「날짜 목록은 언제나 오름차순이다」 |
| 취소와 복원 | `inventory/InventoryService.kt` `cancel()` | | `inventory/InventoryRestoreTest.kt` |
| 동시 취소 차단 (조건부 UPDATE) | `persistence/Repositories.kt` `ReservationRepository.transition()` | | `inventory/InventoryCancelConcurrencyTest.kt` (`T5` `T6`) |
| 테이블 간 락 순서 (ADR-0011) | `inventory/InventoryService.kt` `cancel()` 의 진입 순서 | | `inventory/InventoryCancelConcurrencyTest.kt` 「테이블 간 순서가 어긋나면 실제로 데드락이 난다」 |
| 예약 API 멱등 계약 (`#64`) | `api/ReservationController.kt` · `persistence/Repositories.kt` `findByChannelAndChannelReservationId()` | `V1` UNIQUE | `api/ReservationApiTest.kt` |
| 오버부킹 한도 (`total` 계산값) | `persistence/DailyInventory.kt` | `V1` CHECK | `inventory/InventoryDeductionTest.kt` · `SchemaMigrationTest.kt` |
| 상태 전이표와 점유 집합 | `domain/ReservationStatus.kt` | `V1` CHECK | `domain/ReservationStatusTest.kt` (DB 없이 실행) |
| Outbox 적재 (dual-write 제거) | `inventory/InventoryService.kt` 안의 `outbox` 삽입 · `persistence/OutboxEvent.kt` | `V1` | `inventory/InventoryDeductionTest.kt` 「통보가 예약과 같은 트랜잭션에 적힌다」 · `ArchitectureTest.kt` |
| 릴레이 발행과 재시도 | `outbox/relay/OutboxRelay.kt` `drain()` `markPublished()` `scheduleRetry()` `backoffFor()` | | `outbox/OutboxRelayTest.kt` (`T4`) |
| 집기와 임대를 한 문장으로 (`B3`) | `outbox/relay/OutboxRelay.kt` `claimPending()` | | `outbox/OutboxRelayMultiInstanceTest.kt` |
| 레이트 리밋을 실패로 세지 않기 | `outbox/relay/OutboxRelay.kt` `scheduleRetry(countAsFailure = false)` | | `ops/OutboxDlqTest.kt` |
| DLQ 와 수동 재투입 | `outbox/relay/OutboxRelay.kt` `markDead()` · `ops/OutboxOpsController.kt` | | `ops/OutboxDlqTest.kt` |
| 키 단위 버전 스탬프 (`B4`) | `persistence/Repositories.kt` `OutboxEventRepository.nextVersionFor()` · `OutboxRelay.highestPublishedVersion()` `markSuperseded()` | `V2__outbox_key_version.sql` | `outbox/OutboxVersionStampTest.kt` |
| 웹훅 수신 (Inbox) | `webhook/InboundWebhookController.kt` · `webhook/InboundMessageRecorder.kt` `record()` | `V1` | `webhook/InboundWebhookApiTest.kt` · `webhook/InboundRecordingTest.kt` |
| 순서키 NULL 중복 판정 | `persistence/Repositories.kt` `InboundMessageRepository.alreadyReceived()` (`IS NOT DISTINCT FROM`) | `V1` `UNIQUE NULLS NOT DISTINCT` | `PostgresCapabilityTest.kt` · `webhook/InboundRecordingTest.kt` |
| 알림 처리와 도메인 변경을 한 커밋으로 | `webhook/InboundMessageProcessor.kt` `processOne()` | | `webhook/WebhookIdempotencyTest.kt` (`T3`) |
| 자기 호출로 트랜잭션이 사라지는 경로 봉인 | `webhook/InboundMessageWorker.kt` 와 `InboundMessageProcessor.kt` 를 **빈 두 개로 분리** | | `webhook/WebhookIdempotencyTest.kt` 「처리 표시와 도메인 변경이 같은 트랜잭션이다」 |
| 순서키 정규화와 묘비 (`#72`) | `channel/ChannelAdapter.kt` `sequenceRank()` · `webhook/InboundMessageRecorder.kt` `rankFor()` · `InboundMessageProcessor.handleCreated()` · `Repositories.kt` `hasLaterCancel()` | `V4__sequence_rank.sql` | `webhook/WebhookOrderingTest.kt` |
| Inbox 워커 집기-임대 (`#66`) | `persistence/Repositories.kt` `InboundMessageRepository.claimPending()` | `V5__inbound_lease.sql` | `webhook/InboundLeaseTest.kt` |
| 정기 재동기화 (`B6`) | `resync/InventoryResyncService.kt` `resync()` `runCycle()` | `V3__resync_cursor.sql` | `resync/InventoryResyncTest.kt` |
| 키셋 커서 (`#71`) | `resync/InventoryResyncService.kt` `readCursorFor()` `writeCursor()` | `V3` | `resync/InventoryResyncTest.kt` 「상한을 넘는 격자가 여러 주기에 걸쳐 전부 덮인다」 |
| 재동기화 단일 실행 임대 (`#67`) | `resync/InventoryResyncService.kt` `acquireLease()` `releaseLease()` (펜싱 토큰) | `V3` | `resync/InventoryResyncTest.kt` · `ops/ResyncOpsApiTest.kt` |
| 채널별 노출 상한 (`T11` `T12`) | `policy/ChannelPolicyService.kt` `setCap()` `removeCap()` · `channel/RecordingChannelAdapter.kt` 의 `min(캡, 잔여)` | `V1` `channel_policy` | `policy/ChannelCapTest.kt` |
| 정책 통보 레인 분리 | `policy/ChannelPolicyService.kt` `appendPolicyEvent()` (`aggregate_type` 이 순서 키에 들어간다) | `V2` | `policy/ChannelCapTest.kt` 「정책 통보가 재고 통보를 낡게 만들지 않는다」 |
| 재고 diff 리포트 (`B1`) | `ops/InventoryDiffController.kt` | | `ops/InventoryDiffTest.kt` |
| 지표 2종 (`B5`) | `ops/MetricsController.kt` · `ops/OverbookingPreventedCounter.kt` | | `ops/MetricsTest.kt` |
| 운영 표면 보호와 헬스체크 | `ops/OpsSecurityConfig.kt` · `ops/OpsConsoleController.kt` | | `ops/OpsSecurityTest.kt` |
| DB 예외를 503 으로 (`#65`) | `api/ApiExceptionHandler.kt` | | `api/ApiExceptionHandlerTest.kt` |
| 어댑터 팬아웃 미결의 봉인 | `outbox/relay/OutboxRelay.kt` 생성 시 `require(adapters.size <= 1)` | | 부팅에서 끊습니다 (`#18`) |

---

## 2. 테스트 파일 지도

파일 이름이 곧 증명 대상입니다. `T`·`B` 번호는 [`03-testing-strategy.md`](03-testing-strategy.md) 와
[`06-backlog.md`](06-backlog.md) 의 것과 같습니다.

### 도메인 정합성

| 파일 | 무엇을 증명하나 |
|---|---|
| `inventory/InventoryConcurrencyTest.kt` | `T1` 동시 100건에 성공 1건, `T2` 교차 200회에 데드락 0건. 어긋난 순서로 데드락을 확정 재현하는 대조군까지 |
| `inventory/InventoryCancelConcurrencyTest.kt` | `T5` 동시 취소 50건에 복원 1회, `T6` 취소와 예약의 교차. 테이블 간 순서의 증명 |
| `inventory/InventoryDeductionTest.kt` | 반개구간 차감, 부분 성공 없음, 통보가 같은 트랜잭션, 통보 본문이 사건 시점의 값 |
| `inventory/InventoryRestoreTest.kt` | 복원의 대칭, 차감된 적 없는 상태는 복원하지 않음, 격자가 없으면 전체 롤백 |
| `domain/ReservationStatusTest.kt` | 상태 전이 규칙과 점유·선점 집합. **DB 를 쓰지 않습니다** |

### 나가는 쪽

| 파일 | 무엇을 증명하나 |
|---|---|
| `outbox/OutboxRelayTest.kt` | `T4` 재발행의 실질 부작용 1회, payload 재조회 금지, 백오프 1·2·4·8·15·30분 |
| `outbox/OutboxRelayMultiInstanceTest.kt` | `B3` 인스턴스 2대에서 각 이벤트가 정확히 한 번. 임대는 상태가 아니라 시각 |
| `outbox/OutboxVersionStampTest.kt` | `B4` 낡은 통보가 최신을 덮지 않음, 배치 안 병합, 건너뛴 것은 `PUBLISHED` 가 아님 |
| `ops/OutboxDlqTest.kt` | 레이트 리밋은 반복돼도 `DEAD` 로 가지 않음, 재투입이 예산까지 되돌림 |

### 들어오는 쪽

| 파일 | 무엇을 증명하나 |
|---|---|
| `webhook/InboundWebhookApiTest.kt` | 수신은 202 로 얕게, 중복에 2xx (4xx 면 채널이 재시도를 계속합니다) |
| `webhook/InboundRecordingTest.kt` | 순서키가 null 이어도 조기 반환이 중복을 알아봄, payload 는 받은 그대로 |
| `webhook/WebhookIdempotencyTest.kt` | `T3` 같은 알림 10회에 예약 1건, 처리 표시와 도메인 변경이 한 커밋 |
| `webhook/WebhookOrderingTest.kt` | `#72` 숫자 순서키 정규화, 묘비, 순서키 없는 채널의 취소 유실 방지 |
| `webhook/InboundLeaseTest.kt` | `#66` 집은 건이 다시 잡히지 않고, 만료된 임대는 다시 잡힘 |

### 수렴과 운영

| 파일 | 무엇을 증명하나 |
|---|---|
| `resync/InventoryResyncTest.kt` | `B6` 발행-마킹 창을 되돌림, `#71` 키셋 커서, `#67` 단일 실행 임대. 재동기화가 재고를 바꾸지 않는 것까지 |
| `ops/InventoryDiffTest.kt` | 조용히 누락된 날짜 검출, 자동 보정을 하지 않음 |
| `ops/MetricsTest.kt` | 격자 없음 거절을 방어 실적에 섞지 않음, 건너뛴 통보를 지연 분포에 넣지 않음 |
| `policy/ChannelCapTest.kt` | `T11` `min(캡, 잔여)`, `T12` 캡을 걸고 지워도 재고 모델이 그대로 |
| `ops/OpsSecurityTest.kt` | `/ops` 전부가 키 없이는 401, readiness 가 DB 를 실제로 봄 |
| `ops/ResyncOpsApiTest.kt` · `ops/ChannelPolicyApiTest.kt` · `ops/OutboxDlqTest.kt` | 운영 엔드포인트의 계약 |

### 구조와 스키마를 고정하는 것

| 파일 | 무엇을 증명하나 |
|---|---|
| `ArchitectureTest.kt` | 연관 매핑 금지, 재고 카운터는 `InventoryService` 밖에서 바뀌지 않음, 릴레이와 재동기화의 격리. **DB 를 쓰지 않습니다** |
| `SchemaMigrationTest.kt` | 제약이 실제로 거부하는지. 성격이 셋으로 갈립니다 (거부 · 카탈로그 · **막히지 않아야 하는 것**) |
| `SchemaMatchesErdTest.kt` | ERD 와 실제 스키마의 테이블·컬럼·타입·PK·FK 일치. 파싱 실패가 통과로 읽히지 않게 하는 게이트 포함 |
| `persistence/EntityMappingTest.kt` | 엔티티 왕복, 날짜 목록이 오름차순, `data class` 를 쓰지 않는 이유 |
| `support/InvariantDetectionTest.kt` | **불변식 검사가 실제로 위반을 잡는지.** 검사기 자신을 검사합니다 |
| `support/InvariantHookReachabilityTest.kt` · `InvariantHookJUnitReachabilityTest.kt` | 훅이 두 엔진에 실제로 걸려 있는지. 안 걸려 있으면 검사가 통째로 안 도는데 전부 초록입니다 |
| `PostgresCapabilityTest.kt` | 서버 버전 15 이상, 기본 `UNIQUE` 가 못 막는 것과 `NULLS NOT DISTINCT` 가 막는 것 |

### 테스트 지원 코드

| 파일 | 역할 |
|---|---|
| `inventory/ConcurrencyHarness.kt` | 동시 요청 헬퍼. **새어 나온 예외를 모아 실패로 만듭니다.** 이것이 없어서 반증이 통과한 적이 있습니다 |
| `support/InventoryInvariants.kt` | INV-1 ~ INV-4 판정. 위반을 **전부 모아서** 던집니다 |
| `support/InvariantHook.kt` · `InvariantAfterTestCallback.kt` | Kotest 와 JUnit 양쪽에 훅을 거는 지점 |
| `inventory/InventoryFixture.kt` · `support/OpsRequests.kt` | 픽스처와 운영 API 호출 헬퍼 |
| `PostgresTestContainer.kt` | Testcontainers PostgreSQL 16. H2 를 쓰지 않는 이유는 `03-testing-strategy.md` |

---

## 3. 방어와 그것을 지웠을 때 실패하는 것

지울 자리를 파일 단위로 짚습니다. 실행 명령은 [`09-how-to-verify.md`](09-how-to-verify.md) §2 에 있습니다.

| 지울 것 | 어느 파일의 어디 | 실패해야 하는 것 |
|---|---|---|
| 재고 행 락 | `Repositories.kt` `lockForUpdate()` 를 `findById` 로 | `InventoryConcurrencyTest` `T1` 계열 · `T2` |
| 조건부 UPDATE 의 상태 조건 | `Repositories.kt` `transition()` 의 `AND status = ...` | `InventoryCancelConcurrencyTest` `T5` · `T5` 확장 |
| 어댑터의 멱등키 흡수 | `channel/RecordingChannelAdapter.kt` | `OutboxRelayTest` `T4` |
| 집기-임대 | `OutboxRelay.claimPending()` 의 `SKIP LOCKED` CTE | `OutboxRelayMultiInstanceTest` `B3` |
| 버전 스탬프 판정 | `OutboxRelay.highestPublishedVersion()` 비교 | `OutboxVersionStampTest` `B4` 계열 |
| 레이트 리밋 예산 면제 | `OutboxRelay` 의 `countAsFailure = false` | `OutboxDlqTest` 2종 |
| 순서 키의 `aggregate_type` | `V2__outbox_key_version.sql` 과 `nextVersionFor()` | `ChannelCapTest` 레인 분리 |
| 조기 반환의 `IS NOT DISTINCT FROM` | `Repositories.kt` `alreadyReceived()` | `InboundRecordingTest` 순서키 null 판정 |
| `NULLS NOT DISTINCT` | `V1__init.sql` 의 `inbound_message` | `PostgresCapabilityTest` |
| 엔티티 연관 매핑 금지 | 엔티티에 `@ManyToOne` 을 하나 추가 | `ArchitectureTest` |
| 불변식 훅 등록 | `support/InvariantHook.kt` 등록을 한쪽만 | 도달 테스트 2종 |

> 반증이 통과하면 반증 절차를 먼저 의심합니다. 실제로 네 번 있었고 네 번 다 원인이
> 테스트였습니다. 무엇이 잘못돼 있었는지는 [`03-testing-strategy.md`](03-testing-strategy.md) 에 표로 있습니다.

---

## 4. 이 문서를 함께 고쳐야 하는 때

- 파일이나 함수 이름이 바뀔 때. 지도가 코드보다 낡으면 지도가 아니라 오답입니다
- 새 방어를 넣을 때. §1 에 한 줄, §3 에 지울 자리를 함께 적습니다
- 새 마이그레이션을 넣을 때. §1 의 스키마 칸을 채웁니다

지도에 줄 번호를 적고 싶어지면 그것은 함수가 너무 길다는 신호입니다.
