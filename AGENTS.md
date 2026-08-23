# AGENTS.md

이 파일은 코딩 에이전트를 위한 프로젝트 규약이다.
`CLAUDE.md`는 이 파일로의 심볼릭 링크다. 수정은 `AGENTS.md`에서 한다.

---

## 프로젝트 성격

다채널로 판매되는 숙박 재고의 **정합성**을 증명하는 최소 백엔드 실험이다.

**기능의 폭이 아니라 하나의 문제를 끝까지 증명하는 것이 목표다.**
기능 추가 제안은 기본적으로 반대 방향이다.

작업 전 반드시 읽을 것:
- `README.md` — 문제, 가정, 범위
- `docs/02-scope.md` — 무엇을 만들지 않기로 했는지
- `docs/adr/` — 설계 결정과 **기각한 대안**
- `docs/07-reconciliation.md` — 채널과의 대사 절차 (인바운드를 건드리면 필수)

---

## 기술 스택

```
Kotlin 2.x + Spring Boot 3.x + JDK 21
PostgreSQL 16 + Flyway
Kotest(FunSpec) + Testcontainers + Awaitility + ArchUnit
Gradle (Kotlin DSL)
```

컴파일러 플러그인: `kotlin("plugin.spring")`, `kotlin("plugin.jpa")`

---

## Kotlin 사용 범위 — 의도적으로 좁다

ADR-0005에서 확정한 제한이다. **이 범위를 벗어나는 코드는 작성하지 않는다.**

### 사용한다

- `data class` — DTO, 이벤트 페이로드, 값 객체 (**엔티티 제외**)
- `sealed interface` + `when` — 어댑터 결과 타입
- null 안전성 (`?`, `?:`, `requireNotNull`)
- `val` 기본. `var`는 정당화가 필요
- 확장 함수 — 총 3개 이내

### 사용하지 않는다

| 금지 | 이유 |
|---|---|
| 코루틴 | 데이터 접근이 블로킹 JDBC다. R2DBC 없이는 이득 0이고, R2DBC로 가면 JPA가 사라진다. 트랜잭션 컨텍스트도 ThreadLocal 기반이라 절대 규칙 3이 취약해진다 (ADR-0005) |
| 가상 스레드 | 상한이 스레드가 아니라 커넥션 풀에 있어 기여하지 않는다. JDK 21에서는 pinning 위험만 남는다 (ADR-0006) |
| DSL 빌더 | 읽는 비용 대비 이득 없음 |
| `inline` + `reified` | 이 규모에서 정당화되지 않음 |
| context receivers | 실험적이며 낯설다 |
| 위임 (`by`) | 위임 대상 추적 비용 |
| 연산자 오버로딩 | 도메인 개념을 기호로 숨김 |
| `!!` | `requireNotNull(x) { "메시지" }` 또는 nullable 처리 |

---

## 절대 규칙

### 1. JPA 엔티티에 `data class`를 쓰지 않는다

```kotlin
// ❌
@Entity
data class Reservation(...)

// ✅
@Entity
class Reservation(
    @Id @GeneratedValue val id: Long? = null,
    ...
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Reservation && id != null && id == other.id)
    override fun hashCode(): Int = javaClass.hashCode()
}
```

자동 생성 `equals`/`hashCode`가 전체 프로퍼티 기반이라
지연 로딩 프록시 초기화, 영속성 컨텍스트 동일성 불일치,
영속 전후 해시 변경 문제를 일으킨다.

### 2. 락 획득 순서를 고정한다 — 테이블 사이, 그리고 테이블 안

```
reservation  ->  daily_inventory  ->  channel_allotment
                  └─ stay_date 오름차순
                  └─ inventory_hold 는 이 락 아래에서만 (절대 규칙 11)
```

**한 트랜잭션 안의 순서다. 트랜잭션 분할이 아니다.**
데드락은 한 트랜잭션이 락을 둘 이상 쥘 때만 성립하므로,
순서를 정한다는 것은 여러 개를 동시에 쥔다는 전제를 담고 있다.
쪼개면 데드락은 사라지지만 **원자성과 절대 규칙 3 이 무너진다** (ADR-0011 기각 대안 4).

`reservation` 이 앞인 이유는 조건부 UPDATE 가 거부되면 **재고 락을 아예 잡지 않아도 되기 때문**이다.
거부될 요청이 재고 행을 붙잡고 있을 이유가 없다.

`daily_inventory` 안에서는 다일 예약이 여러 행을 잠그므로 `stayDate` 오름차순으로 고정한다.
정렬을 제거하면 `T2` 가, 테이블 간 순서를 뒤집으면 `T6` 이 실패해야 한다. (ADR-0002 · ADR-0011)

### 3. `OutboxEvent` 저장은 도메인 변경과 같은 트랜잭션이다

분리되면 dual-write 문제가 그대로 재발한다. 이것이 이 프로젝트의 존재 이유다.

### 4. 락 보유 구간에서 외부 I/O를 호출하지 않는다

### 5. 중복 웹훅에 2xx를 반환한다

4xx를 주면 채널이 실패로 간주해 최대 24시간 재시도한다.
멱등하게 처리했다면 그것은 성공이다.

### 6. 숙박 기간은 `[checkIn, checkOut)` 반개구간이다

체크아웃 당일 재고를 차감하지 않는다.

### 7. 재고 복원은 `CONFIRMED -> CANCELED`에서만 일어난다

선점 상태(`HELD` · `PENDING_APPROVAL`)에서 오는 종료는 차감된 적이 없다.
이 비대칭을 놓치면 재고가 부풀어 오른다.

### 8. H2를 쓰지 않는다

`SELECT FOR UPDATE` 시맨틱이 PostgreSQL과 달라 락 검증이 성립하지 않는다.
**DB 를 만지는 테스트는 전부** Testcontainers 기반 실제 PostgreSQL 에서 실행한다.
H2 를 쓰지 않는 이유는 `SELECT FOR UPDATE` 의 의미가 달라 락 검증이 성립하지 않기 때문이다.

DB 를 만지지 않는 스펙이 둘 있다 — `ReservationStatusTest`(상태 전이표)와
`ArchitectureTest`(클래스 파일만 읽는다). **둘은 결함이 아니라 `domain` 과
`persistence` 를 가른 것의 의도된 효과다**(ADR-0008) — 상태 규칙이 틀렸는지 보려고
컨테이너를 띄울 이유가 없다.

### 9. 인바운드 알림은 Inbox 를 거친다

웹훅을 받아 바로 처리하지 않는다.
`inbound_message` 에 먼저 적고 즉시 2xx 를 준 뒤, **별도 워커가 처리한다.**
처리 표시(`status = PROCESSED`)는 도메인 변경과 **같은 트랜잭션**이다.

**Outbox 의 거울상이며 근거도 같다**(ADR-0003) —
Outbox 가 없으면 "DB 는 바뀌었는데 통보가 안 나간" 상태가 생기고,
Inbox 가 없으면 "알림은 왔는데 처리는 안 된" 상태가 생긴다.

외부 큐의 확인(ack) 절차를 쓰는 경우에만, **확인은 커밋 뒤에** 한다.
확인을 먼저 하고 롤백되면 그 알림은 영구히 사라진다 — 유실이다.
**유실과 중복 중 중복을 택한다.**

### 10. 채널 웹훅 payload 의 값을 상태에 반영하지 않는다

채널매니저 공개 문서가 웹훅의 순서 보장이 없다고 명시한다.
payload 는 **트리거로만** 쓰고, 조회 경로가 있으면 현재 상태를 당겨온다.
조회 경로가 없으면 payload 의 **순서 키**로 정렬해 처리하고,
순서 키조차 없는 채널은 그 제약을 어댑터별로 문서에 남긴다.

이 규칙을 어기면 순서 역전이 그대로 내부 상태가 된다.

### 11. `inventory_hold` 는 `daily_inventory` 행 락 아래에서만 만진다

`inventory_hold` 행은 해당 `(room_type_id, stay_date)` 의 `daily_inventory` 행 락을
보유한 상태에서만 읽고 쓴다.

선점 잡기는 그 재고 행을 `FOR UPDATE` 로 잠그지만 **값을 바꾸지 않는다.**
그 행은 순수하게 뮤텍스로 쓰인다 — 그래서 없어도 되는 것처럼 보인다.
지우면 집계와 INSERT 사이에 다른 트랜잭션이 끼어들어 **과선점이 열리고**,
`INV-4` 가 깨진 원인을 역추적하기가 매우 어렵다.

절대 규칙 2 와 같은 성질이다. 지우기 쉬운 한 줄이 방어의 전부다.
제거하면 테스트 `T8` 이 실패해야 한다.

**그리고 이 규칙 때문에 `inventory_hold` 는 락 순서에 새 단계를 만들지 않는다.**
선점을 만지는 모든 트랜잭션이 먼저 재고 행 락을 지나가므로 순환에 참여할 수 없다.
(ADR-0010 · ADR-0011)

### 12. 엔티티 간 연관 매핑을 두지 않는다 — 참조는 ID 로만 갖는다

```kotlin
// ❌
@ManyToOne(fetch = LAZY) val roomType: RoomType

// ✅
@Column(name = "room_type_id") val roomTypeId: Long
```

dirty checking 은 **정적 참조 없이 UPDATE 를 만든다.**
연관을 타고 들어가 필드를 바꾸면 `InventoryService` 도 Repository 도 거치지 않는데
쓰기가 일어나고, ArchUnit 의 "재고 변경은 `InventoryService` 를 통해서만" 규칙이 이를 잡지 못한다.
연관 매핑을 두지 않으면 **그 경로 자체가 만들어지지 않는다.** (ADR-0008)

---

## 제안하지 말 것

아래는 ADR에서 **이미 검토하고 기각한** 대안이다. 다시 제안하지 않는다.

| 항목 | 근거 문서 |
|---|---|
| Kafka / 메시지 브로커 도입 | ADR-0004 (도입 조건 4개와 마이그레이션 경로 명시됨) |
| `@TransactionalEventListener(AFTER_COMMIT)` | ADR-0003 (커밋 후 프로세스 종료 시 이벤트 소실) |
| 낙관적 락 (`@Version`) | ADR-0002 (고경합 워크로드에서 재시도 폭발) |
| SERIALIZABLE 격리 수준 | ADR-0002 |
| Redis 분산 락 | ADR-0002 |
| 코루틴 도입 | ADR-0005 |
| 가상 스레드 활성화 (`spring.threads.virtual.enabled`) | ADR-0006 |
| pinning 회피 목적의 JDK 24/25 이관 | ADR-0006 (편익이 0이므로 비용만 남음) |
| jOOQ / 순수 JdbcTemplate 전면 전환 | ADR-0008 (조건부 채택. 재검토 조건 4개 명시됨) |
| R2DBC 전환 | ADR-0008 (트랜잭션이 사는 동안 커넥션 점유는 그대로 남는다), ADR-0006 |
| 엔티티 연관 매핑 추가 (`@OneToMany`/`@ManyToOne`) | ADR-0008 (절대 규칙 9) |
| Lincheck 등 모델 체킹 | ADR-0005 (검증 대상이 인메모리 자료구조라 적용 불가) |
| Java 전환 | ADR-0005 |
| Allocated inventory | ADR-0001 |
| 채널별 재고 카운터 (hybrid 의 순진한 형태) | ADR-0009 (Channex 가 채널별 availability push 를 제공하지 않음) |
| LWW — 최신 타임스탬프가 이기는 충돌 해소 | ADR-0009 (재고 축의 원본이 둘이 된다. 시각 기반 판정은 신뢰성·정밀도·동률 처리 확인 전까지 쓰지 않는다) |
| 채널 변경을 항상 우리 값으로 덮어쓰기 (교정 전용) | ADR-0009 (현장 조작과 무한 루프) |
| 인증/권한 추가 | `docs/02-scope.md` (명시적 범위 제외) |
| 프론트엔드 | `docs/02-scope.md` |
| 요금(Rate) 정책 엔진 | `docs/02-scope.md` (직교하는 문제 축) |
| 부하 테스트 도구 (nGrinder/k6/Gatling) | `docs/03-testing-strategy.md` |

기각 논거에 실제로 오류가 있다고 판단되면, 제안이 아니라
**어느 ADR의 어느 문장이 틀렸는지**를 지적한다.

---

## 테스트 규약

테스트 이름은 "무엇을 했는가"가 아니라 **"무엇을 증명하는가"**를 서술한다.

```kotlin
test("동시 요청 하에서 오버부킹은 발생하지 않는다") { ... }
```

- **기본은 Kotest `FunSpec`.** 다만 절대 규칙이 아니다 — JUnit 스타일이 명백히 쉬운
  자리(`@ParameterizedTest`, 일부 Spring 테스트 슬라이스, JUnit 전용 확장)에서는 쓴다.
  **조건이 하나 붙는다: 아래 불변식 훅이 두 엔진 모두에 걸려 있어야 한다**
- Given-When-Then 주석 구조. AAA 아님
- 동시성 테스트는 `CountDownLatch` 등으로 **실제 동시 시작을 강제**한다
- **모든 테스트 종료 시 불변식을 검증한다** — 이것이 위 항목의 유일한 제약 조건이다

#### 왜 스타일 혼용에 조건이 붙는가

불변식 훅을 Kotest 확장으로만 만들면 **JUnit 스타일 테스트는 검사를 조용히 건너뛴다.**
통과했다는 신호는 그대로 나오고 검사만 사라진다 — 이 저장소가 막겠다고 하는 실패 형태다.

그래서 훅을 프레임워크 하나에 묶지 않는다.

| 엔진 | 등록 방법 |
|---|---|
| Kotest | `AfterTestListener` 를 `ProjectConfig` 에 등록 |
| JUnit Jupiter | `AfterTestExecutionCallback` + **자동 등록**(`junit-platform.properties` 의 `junit.jupiter.extensions.autodetection.enabled=true` + `ServiceLoader`) |

**JUnit 쪽은 애노테이션을 붙이지 않아도 걸리게 한다.** 붙이는 것을 잊으면 검사가
빠지므로, 잊을 수 있는 형태로 두지 않는다.

그리고 ArchUnit 으로 확인한다 — **의존성 배제로는 막을 수 없다**(`kotest-runner-junit5` 가
`junit-bom` 을 통해 Jupiter API 를 끌어온다). 검사로 막는다.

```
INV-1  0 <= sold <= total
       total = physical_total + overbooking_limit   계산값이다 (ADR-0007)
INV-2  sold == 해당 날짜를 포함하고 재고를 점유 중인 예약의 room_count 합
       점유 상태 = { CONFIRMED, CHECKED_IN, CHECKED_OUT, TERMINATED }
       차감된 적 없음(HELD·PENDING_APPROVAL·EXPIRED·REJECTED)과
       복원됨(CANCELED)은 둘 다 제외한다
INV-3  checkIn < checkOut
INV-4  sold + SUM(유효 선점의 room_count) <= total   과선점 금지 (ADR-0010)
       유효 선점 = expires_at > now() AND released_at IS NULL
```

### 안정성 테스트 (프로젝트의 코어)

**T1~T4 가 차단선이다.** 토요일 23:00 에 이 넷이 통과하지 않으면 나머지 백로그는 폐기 대상이다.

| # | 증명 대상 | 지우면 실패해야 하는 것 |
|---|---|---|
| T1 | 오버부킹 물리적 불가 (동시 100건 → 성공 정확히 1건) | 재고 행 락 |
| T2 | 다일 예약 데드락 부재 (역순 요청 반복) | `stayDate` 정렬 (절대 규칙 2) |
| T3 | at-least-once 웹훅 멱등 흡수 | `UNIQUE(channel, channel_reservation_id)` |
| T4 | Outbox 재발행 시 부작용 1회 | 멱등키 |

확정 경로 대칭과 확정 전 구간은 별도다. **차단선에는 들어가지 않는다.**

| # | 증명 대상 | 지우면 실패해야 하는 것 |
|---|---|---|
| T5 | 중복 취소는 재고를 두 번 복원하지 않는다 | 조건부 UPDATE 의 `AND status = 'CONFIRMED'` (#26) |
| T6 | 취소·예약 교차 시 데드락 부재 | `reservation -> daily_inventory` 순서 (#27) |
| T7 | 만료된 선점은 재고를 잠그지 않는다 | `expires_at > now()` 조건 |
| T8 | 선점 경합에서 과선점 부재 | `daily_inventory` 행 락 (**절대 규칙 11**) |
| T9 | 승인 대기 중 재고가 팔리지 않는다 | `INV-4` 의 `released_at IS NULL` |
| T10 | 만료 후 결제 성공은 재고 없으면 확정 안 된다 | 전이 시 선점 재검증 |

**테스트를 작성한 뒤 반드시 대상 로직을 의도적으로 깨뜨려 실패하는지 확인한다.**
통과하는 테스트는 아무것도 증명하지 않는다. 깨뜨렸을 때 실패해야 증명이다.

---

## 작업 방식

### 브랜치와 PR

수직 슬라이스 단위로 쪼갠다. 슬라이스마다 PR을 연다.

```
feat/domain-model        엔티티 + Flyway V1
feat/inventory-deduction 재고 차감 + T1, T2
feat/outbox-relay        Outbox + 어댑터 + T4
feat/idempotent-webhook  웹훅 수신 + T3
feat/ops-endpoints       백로그 B1, B2
```

### 커밋

Conventional Commits: `feat:`, `fix:`, `docs:`, `test:`, `refactor:`, `chore:`

**커밋 메시지, 코드, 문서 어디에도 특정 회사명이나
"과제 / assignment / interview / 지원" 같은 단어를 쓰지 않는다.**
이 저장소는 개인 기술 실험이다.

**예외: 외부 채널·OSS 의 공개 스펙을 인용할 때는 출처명을 적는다.**
근거의 출처를 지우면 "원문에서 직접 확인했다"(`docs/05-ai-collaboration.md`)는 주장 자체가
검증 불가능해진다. 이 규칙이 막으려는 것은 지원·과제 흔적이지 기술 출처가 아니다.

### AI 리뷰 대응

이 저장소는 **CodeRabbit 단독**으로 PR 리뷰를 받는다. 설정: `.coderabbit.yaml`

Gemini Code Assist 를 함께 쓰다가 무료 제공 종료로 중단했다.
판단 경위와 되돌릴 조건은 `docs/05-ai-collaboration.md` 에 있다.

봇 지적을 **조용히 반영하지 않는다.** 답글로 판단을 남긴다.

```
수용 → 수정 커밋 링크 + 왜 타당했는지
기각 → 근거 (도메인 제약 / 범위 밖 / 오탐)
보류 → 백로그 전환 + 이슈 링크
```

판단이 필요했던 건은 `docs/05-ai-collaboration.md` 표에 기록한다.

---

## 우선순위와 차단선

```
Tier 0  코어 — 없으면 정합성 주장이 무너짐
Tier 1  운영 — diff 리포트, Outbox DLQ
Tier 2  깊이 — SKIP LOCKED, 배치 병합, 지표
Tier 3  문서만 — Kafka, Debezium, 토큰버킷
```

**Tier 0가 완결되기 전에 Tier 1 이하를 착수하지 않는다.**
안정성 테스트 4종이 통과하지 않으면 백로그는 전부 폐기 대상이다.

시간이 남아도 **기능을 추가하지 않는다. 깊이를 추가한다.**
`docs/06-backlog.md` 참고.

---

## 문서를 고칠 때

코드와 문서가 어긋나면 **문서를 고치지 말고 먼저 물어본다.**
문서는 판단의 기록이므로, 구현 편의로 사후 수정하면 기록의 의미가 사라진다.

새로운 설계 결정이 생기면 새 ADR을 추가한다. 기존 ADR을 덮어쓰지 않는다.
