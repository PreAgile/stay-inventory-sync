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
| 코루틴 | JDK 21 가상 스레드를 쓴다. 구조적 동시성이 필요한 지점 없음 |
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

### 2. 락 획득 순서를 `stayDate` 오름차순으로 고정한다

다일 예약은 여러 재고 행을 잠근다. 순서가 요청마다 다르면 데드락이 발생한다.
정렬을 제거하면 테스트 T2가 실패해야 한다.

### 3. `OutboxEvent` 저장은 도메인 변경과 같은 트랜잭션이다

분리되면 dual-write 문제가 그대로 재발한다. 이것이 이 프로젝트의 존재 이유다.

### 4. 락 보유 구간에서 외부 I/O를 호출하지 않는다

### 5. 중복 웹훅에 2xx를 반환한다

4xx를 주면 채널이 실패로 간주해 최대 24시간 재시도한다.
멱등하게 처리했다면 그것은 성공이다.

### 6. 숙박 기간은 `[checkIn, checkOut)` 반개구간이다

체크아웃 당일 재고를 차감하지 않는다.

### 7. 재고 복원은 `CONFIRMED -> CANCELED`에서만 일어난다

`PENDING -> CANCELED`는 차감된 적이 없다. 이 비대칭을 놓치면 재고가 부풀어 오른다.

### 8. H2를 쓰지 않는다

`SELECT FOR UPDATE` 시맨틱이 PostgreSQL과 달라 락 검증이 성립하지 않는다.
모든 테스트는 Testcontainers 기반 실제 PostgreSQL에서 실행한다.

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
| Lincheck 등 모델 체킹 | ADR-0005 (검증 대상이 인메모리 자료구조라 적용 불가) |
| Java 전환 | ADR-0005 |
| Allocated inventory | ADR-0001 |
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

- Kotest `FunSpec`. JUnit으로 전환하지 않는다
- Given-When-Then 주석 구조. AAA 아님
- 동시성 테스트는 `CountDownLatch` 등으로 **실제 동시 시작을 강제**한다
- 모든 테스트 종료 시 불변식을 검증한다

```
INV-1  0 <= sold <= total
INV-2  sold == 해당 날짜를 포함하는 CONFIRMED 예약 수
INV-3  checkIn < checkOut
```

### 안정성 테스트 4종 (프로젝트의 코어)

| # | 증명 대상 |
|---|---|
| T1 | 오버부킹 물리적 불가 (동시 100건 → 성공 정확히 1건) |
| T2 | 다일 예약 데드락 부재 (역순 요청 반복) |
| T3 | at-least-once 웹훅 멱등 흡수 |
| T4 | Outbox 재발행 시 부작용 1회 |

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

### AI 리뷰 대응

이 저장소는 CodeRabbit과 Gemini Code Assist를 PR 리뷰어로 쓴다.
설정: `.coderabbit.yaml`, `.gemini/config.yaml`, `.gemini/styleguide.md`

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
