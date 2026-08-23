# 검증 방법 — 주장마다 어떤 명령으로 확인하나

이 저장소의 주장은 전부 **실행해서 확인할 수 있어야 한다.**
"동시성을 처리했다" 는 검증되지 않는 문장이고, **"이 줄을 지우면 이 테스트가 실패한다"**
는 검증되는 문장이다.

이 문서는 그 짝을 한곳에 모은다. 읽는 사람이 저장소를 신뢰할 이유를
**저자의 서술이 아니라 자기 손으로** 만들 수 있게 하는 것이 목적이다.

---

## 0. 준비

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # macOS. Gradle 은 JDK 21 로 실행한다
./gradlew test
```

Docker 만 떠 있으면 된다. `docker compose` 는 필요하지 않다 —
Testcontainers 가 실행마다 격리된 PostgreSQL 16 을 띄운다.

전부 통과하면 아래 표의 「증명」 칸이 전부 성립한다.

---

## 1. 핵심 주장 — 테스트로 고정된 것

각 행은 **주장 · 증명하는 테스트 · 그 방어를 지웠을 때 실패하는 것** 세 쌍이다.
마지막 칸이 있어야 테스트가 장식이 아니다.

| 주장 | 실행 | 지우면 실패 |
|---|---|---|
| 동시 요청 하에 오버부킹이 발생하지 않는다 | `--tests '*InventoryConcurrencyTest*'` | 행 락 · 날짜 오름차순 |
| 다일 예약에서 데드락이 나지 않는다 | `--tests '*InventoryConcurrencyTest*'` | 락 순서 고정 (`40P01` 로 확정 재현) |
| 동시 취소에서 재고가 한 번만 복원된다 | `--tests '*InventoryCancelConcurrencyTest*'` | `AND status = 'CONFIRMED'` |
| 같은 웹훅을 두 번 받아도 한 번만 반영된다 | `--tests '*WebhookIdempotencyTest*'` | 조기 반환 · DB `UNIQUE` |
| 순서키가 없는 채널에서도 중복이 막힌다 | `--tests '*PostgresCapabilityTest*'` | `NULLS NOT DISTINCT` |
| **숫자 순서키가 올바른 순서로 처리된다** | `--tests '*WebhookOrderingTest*'` | `sequence_rank` 정렬 (`#72`) |
| **늦게 온 생성이 최신 취소를 덮지 않는다** | `--tests '*WebhookOrderingTest*'` | 묘비 검사 |
| **순서키 없는 채널의 취소가 유실되지 않는다** | `--tests '*WebhookOrderingTest*'` | 멱등 키의 `event_type` |
| **예약 재시도가 중복 예약을 만들지 않는다** | `--tests '*ReservationApiTest*'` | 호출부 멱등 키 (`#64`) |
| **키가 없으면 거부한다** | `--tests '*ReservationApiTest*'` | 서버가 키를 만들지 않는다 |
| 릴레이가 실패한 발행을 다시 보낸다 | `--tests '*OutboxRelayTest*'` | 백오프 스케줄 |
| 인스턴스 2대가 같은 이벤트를 두 번 보내지 않는다 | `--tests '*OutboxRelayMultiInstanceTest*'` | 집기-임대 한 문장 |
| 레이트 리밋이 정상 이벤트를 DEAD 로 보내지 않는다 | `--tests '*OutboxDlqTest*'` | `countAsFailure = false` |
| 낡은 발행이 최신 값을 덮어쓰지 않는다 | `--tests '*OutboxVersionStampTest*'` | 키 단위 버전 비교 |
| 조용히 누락된 날짜를 잡아낸다 | `--tests '*InventoryDiffTest*'` | `null` 과 `0` 의 구분 |
| 재동기화가 발행-마킹 창을 닫는다 | `--tests '*InventoryResyncTest*'` | 스냅샷 절대값 전송 |
| **격자가 상한을 넘어도 전 구간이 덮인다** | `--tests '*InventoryResyncTest*'` | 키셋 커서 (`#71`) |
| **인스턴스가 여러 대여도 한 대만 돈다** | `--tests '*InventoryResyncTest*'` | 조건부 UPDATE 임대 (`#67`) |
| **동시 클릭이 조용히 성공하지 않는다** | `--tests '*ResyncOpsApiTest*'` | 임대 판정 → `409` |
| 채널 캡이 노출을 제한한다 | `--tests '*ChannelCapTest*'` | `min(캡, 잔여)` 를 채널이 계산 |
| 엔티티 간 연관 매핑이 없다 | `--tests '*ArchitectureTest*'` | — (규칙 자체) |
| 불변식 훅이 두 엔진 모두에 걸려 있다 | `--tests '*InvariantHook*ReachabilityTest*'` | 한쪽만 등록하면 실패 |
| ERD 와 실제 스키마가 일치한다 | `--tests '*SchemaMatchesErdTest*'` | 타입·PK·FK 변경 |

### 스키마 제약이 실제로 막는지 보기

```bash
./gradlew test --tests '*SchemaMigrationTest*'
```

15개인데 **성격이 셋으로 갈린다.** 뭉쳐서 "전부 위반 시도" 라고 적으면
검증 범위를 잘못 전달한다.

| 성격 | 개수 | 통과가 뜻하는 것 |
|---|---|---|
| **`SQLSTATE` 거부** | 11 | 그 자리가 실제로 막혀 있다 |
| **카탈로그 확인** | 3 | 스키마의 모양이 그렇다 (테이블 9개 = 도메인 8 + 운영 1 · `total` 컬럼 부재 · 부분 인덱스 정의) |
| **허용 동작** | 1 | **막히지 않아야 하는 것이 막히지 않는다** (재고 행 없는 미래 날짜의 정책) |

세 번째가 하나뿐이지만 성격이 다르다. **제약이 정상 경로를 막지 않는지**도
확인해야 하고, 그것은 거부 테스트로는 볼 수 없다.

---

## 2. 반증 절차 — 방어를 지워서 확인하는 방법

가장 강한 검증은 **방어를 실제로 지우고 테스트가 빨간불이 되는지 보는 것**이다.
아래는 그중 손이 적게 가는 셋이다.

| 지울 것 | 어디 | 기대 |
|---|---|---|
| `AND status = 'CONFIRMED'` | 취소 조건부 UPDATE | 동시 취소 테스트 실패 |
| `NULLS NOT DISTINCT` | `V1__init.sql` 의 `inbound_message` | 순서키 NULL 중복 테스트 실패 |
| `countAsFailure = false` | `OutboxRelay` 의 `RateLimited` 분기 | 429 반복 시 DEAD 로 떨어짐 |

지우고 `./gradlew test` 를 돌린 뒤 **되돌리는 것**을 잊지 않는다.

> **반증이 통과하면 반증 절차를 먼저 의심한다.**
> 이 저장소에서 실제로 네 번 있었고 **네 번 다 원인이 테스트였다.**
> 그때 무엇이 잘못돼 있었는지는 [`03-testing-strategy.md`](03-testing-strategy.md) 에 표로 있다.

---

## 3. 서술만 있는 것 — 테스트가 없는 주장

**이 절이 있어야 위 표가 신뢰된다.** 무엇이 증명되지 않았는지 밝히지 않으면
증명된 것과 섞인다.

| 주장 | 왜 테스트가 없나 |
|---|---|
| DB 가 죽어도 웹훅 알림을 잃지 않는다 | 채널의 재시도에 의존한다. 우리 쪽에서 검증할 수 있는 것은 5xx 를 준다는 것까지다 |
| 레이트 리밋 산정(분당 20회 등) | 외부 공개 스펙 인용이다. 스텁 환경에서는 재현되지 않는다 |
| 6초 배치 간격이 429 를 막는다 | **막지 못한다.** 필요조건이고 충분조건이 아니라고 문서에 적었다 |
| Inbox 워커가 인스턴스마다 중복 시도한다 | 코드를 읽어 확인한 사실이고 테스트는 없다 (`#66`) |

---

## 4. 저장소 구조를 확인하는 명령

읽는 사람이 서술을 믿지 않고 직접 셀 수 있게 한다.

```bash
# 도메인 테이블 8개 (V1) + 운영 1개 (V3)
grep -c '^CREATE TABLE' src/main/resources/db/migration/V1__init.sql
grep -c '^CREATE TABLE' src/main/resources/db/migration/V3__resync_cursor.sql

# ADR 12개 — 각 파일에 「기각한 대안」이 있는지
ls docs/adr/*.md | wc -l
grep -l '기각' docs/adr/*.md | wc -l   # 12 중 11. 나머지 하나는 기각할 대안이 없던 결정이다

# 흐름 경로별 트랜잭션 경계
grep -rn '@Transactional' src/main/kotlin

# 다중 인스턴스 방어 — 릴레이는 SKIP LOCKED, 재동기화는 조건부 UPDATE 임대
grep -rn 'SKIP LOCKED' src/main/kotlin
grep -rn 'leased_until' src/main/kotlin

# 테스트 수
grep -rc 'test(' src/test --include='*.kt' | awk -F: '{s+=$2} END {print s}'
```

다중 인스턴스 방어를 두 명령으로 나눠 확인하는 이유는 **방식이 다르기 때문**이다 —
릴레이는 `SKIP LOCKED` 집기-임대, 재동기화는 `leased_until` 조건부 UPDATE.
**Inbox 워커에는 둘 다 없고**, 그 비대칭의 근거와 공백은
[`08-failure-and-recovery.md`](08-failure-and-recovery.md) §7 에 있다.

---

## 5. 읽는 순서

문서가 23개다. 처음 보는 사람이 30분에 판단하려면 이 순서가 빠르다.

| 순 | 문서 | 왜 여기서 |
|---|---|---|
| 1 | `README.md` 「무엇에 중점을 두었나」 | 판단 기준 셋 |
| 2 | `README.md` 「구조」 | 요청이 어디를 지나는가 |
| 3 | `README.md` 「장애와 회복」 | 못 막는 것이 무엇인가 |
| 4 | `docs/03-testing-strategy.md` 「반증이 통과하면」 | **틀렸던 기록** |
| 5 | `docs/adr/0011` · `0012` | 가장 늦게 배운 것 |
| 6 | `docs/02-scope.md` | 무엇을 안 만들기로 했나 |

**4번을 권한다.** 맞은 기록보다 틀린 기록이 저장소의 성질을 더 많이 말한다.
