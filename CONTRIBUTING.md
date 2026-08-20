# 기여 안내

이 저장소는 **다채널로 판매되는 숙박 재고의 정합성**을 증명하는 최소 백엔드 실험이다.
기능의 폭이 아니라 하나의 문제를 끝까지 증명하는 것이 목표다.

`AGENTS.md`는 코딩 에이전트용 규약이고, 이 문서는 **사람용 진입점**이다.
겹치는 내용은 링크로 대체한다.

---

## 5분 안에 파악하기

읽는 순서가 정해져 있다.

| 순서 | 문서 | 무엇을 알게 되는가 |
|---|---|---|
| 1 | [README](README.md) | 어떤 문제를 왜 골랐는가, 무엇을 만들지 않기로 했는가 |
| 2 | [docs/01-domain-model.md](docs/01-domain-model.md) | ERD, 상태 전이, 불변식 INV-1~3 |
| 3 | [docs/03-testing-strategy.md](docs/03-testing-strategy.md) | 안정성 테스트 4종이 각각 무엇을 증명하는가 |
| 4 | [docs/adr/](docs/adr/) | 설계 결정과 **기각한 대안** |
| 5 | [docs/05-ai-collaboration.md](docs/05-ai-collaboration.md) | AI 위임 경계와 판단 기록 |

이 저장소의 밀도는 코드가 아니라 **기각의 기록**에 있다.
"왜 이렇게 했는가"보다 "왜 저렇게 하지 않았는가"에 시간을 더 썼다.

---

## 이슈를 열기 전에

**제안하려는 것이 이미 기각된 것일 수 있다.** 아래는 ADR에서 검토를 마친 대안이다.

| 항목 | 근거 |
|---|---|
| Kafka / 메시지 브로커 도입 | [ADR-0004](docs/adr/0004-no-kafka-yet.md) — 도입 조건 4개와 마이그레이션 경로 명시 |
| `@TransactionalEventListener(AFTER_COMMIT)` | [ADR-0003](docs/adr/0003-outbox-at-least-once.md) — 커밋 후 프로세스 종료 시 이벤트 소실 |
| 낙관적 락 · SERIALIZABLE · Redis 분산 락 | [ADR-0002](docs/adr/0002-pessimistic-lock.md) |
| 코루틴 · Lincheck · Java 전환 | [ADR-0005](docs/adr/0005-kotlin-subset.md) |
| Allocated inventory | [ADR-0001](docs/adr/0001-pooled-inventory.md) |
| 인증/권한 · 프론트엔드 · 요금 정책 | [docs/02-scope.md](docs/02-scope.md) — 명시적 범위 제외 |
| 부하 테스트 도구 (k6/Gatling/nGrinder) | [docs/03-testing-strategy.md](docs/03-testing-strategy.md) |

기각 논거에 **실제로 오류가 있다고 판단되면**, 제안이 아니라
**어느 ADR의 어느 문장이 틀렸는지**를 지적해달라. 무조건 순응하면 진짜 오류를 놓친다.

---

## 라벨 체계

의미는 색이 아니라 **접두어**에 있다. 색은 보조 신호일 뿐이다.

### `type:` — 무엇을 하는 작업인가

`feature` 수직 슬라이스 · `fix` 결함 수정 · `docs` 문서 · `test` 증명 추가 ·
`adr` 설계 결정 · `chore` 빌드·CI·저장소 설정

### `tier-N:` — 착수 순서

명도 단계로 구분한다. 숫자가 작을수록 진하다.

| 라벨 | 의미 |
|---|---|
| `tier-0: core` | 없으면 정합성 주장이 무너진다. **차단선** |
| `tier-1: ops` | 운영 — 있으면 문제가 완결된다 |
| `tier-2: depth` | 깊이 — 기능이 아니라 증명을 더한다 |
| `tier-3: docs-only` | 구현하지 않고 판단만 기록한다 |

**Tier 0가 완결되기 전에 Tier 1 이하를 착수하지 않는다.**
안정성 테스트 4종이 통과하지 않으면 나머지 백로그는 전부 폐기 대상이다.

### `area:` — 어디를 건드리는가

`inventory` · `outbox` · `channel` · `webhook` · `domain` · `ci`

### 판단 신호

| 라벨 | 의미 |
|---|---|
| `correctness` | 정합성에 영향. 리뷰 시 최우선으로 본다 |
| `needs-decision` | 착수 전 판단이 필요하다. 대개 ADR이 따라붙는다 |
| `blocked` | 선행 이슈가 끝나야 착수 가능 |

마일스톤은 Tier와 1:1로 대응한다. 마일스톤이 닫히는 조건은 각 마일스톤 설명에 있다.

---

## 브랜치와 커밋

수직 슬라이스 단위로 쪼갠다. 슬라이스마다 PR을 연다.

```
feat/domain-model         엔티티 + Flyway V1
feat/inventory-deduction  재고 차감 + T1, T2
feat/outbox-relay         Outbox + 어댑터 + T4
feat/idempotent-webhook   웹훅 수신 + T3
docs/...                  문서·ADR
chore/...                 CI·저장소 설정
```

커밋은 Conventional Commits — `feat:` `fix:` `docs:` `test:` `refactor:` `chore:`

**커밋 메시지·코드·문서 어디에도 특정 회사명을 쓰지 않는다.** 개인 기술 실험이다.

---

## 테스트 규약

테스트 이름은 "무엇을 했는가"가 아니라 **"무엇을 증명하는가"** 를 서술한다.

```kotlin
test("동시 요청 하에서 오버부킹은 발생하지 않는다") { ... }
```

- Kotest `FunSpec`. JUnit으로 전환하지 않는다
- Given-When-Then 주석 구조 (AAA 아님)
- 동시성 테스트는 `CountDownLatch` 등으로 **실제 동시 시작을 강제**한다
- 모든 테스트 종료 시 불변식 INV-1~3을 검증한다
- **H2를 쓰지 않는다.** `SELECT FOR UPDATE` 시맨틱이 PostgreSQL과 달라 락 검증이 성립하지 않는다.
  모든 테스트는 Testcontainers 기반 실제 PostgreSQL에서 돈다

### 가장 중요한 규칙

**테스트를 작성한 뒤 반드시 대상 로직을 의도적으로 깨뜨려 실패하는지 확인한다.**

통과하는 테스트는 아무것도 증명하지 않는다. 깨뜨렸을 때 실패해야 증명이다.
PR 본문에 "무엇을 깨뜨려 확인했는지" 한 줄을 남겨달라.

---

## PR

[PR 템플릿](.github/pull_request_template.md)의 **정합성 체크리스트**를 채운다.
해당 없으면 `N/A — 이유`로 남긴다. 빈칸으로 두지 않는다.

### AI 리뷰 대응

이 저장소는 CodeRabbit과 Gemini Code Assist를 PR 리뷰어로 쓴다.
설정은 `.coderabbit.yaml` · `.gemini/config.yaml` · `.gemini/styleguide.md`.

**봇 지적을 조용히 반영하지 않는다.** 답글로 판단을 남긴다.

```
수용 → 수정 커밋 링크 + 왜 타당했는지 한 줄
기각 → 근거 (도메인 제약 / 범위 밖 / 오탐)
보류 → 백로그 이슈로 전환하고 링크
```

판단이 필요했던 건은 [docs/05-ai-collaboration.md](docs/05-ai-collaboration.md)의 표에 옮긴다.
**PR 스레드 자체가 협업의 증거**이므로, 요약보다 현장의 기록이 우선이다.

---

## 문서를 고칠 때

코드와 문서가 어긋나면 **문서를 고치지 말고 먼저 이슈로 물어본다.**

문서는 판단의 기록이다. 구현 편의로 사후 수정하면 "왜 이렇게 결정했는가"가 증발한다.

새로운 설계 결정이 생기면 **새 ADR을 추가한다. 기존 ADR을 덮어쓰지 않는다.**
기존 판단이 틀렸다면 그 ADR에 정정 이력을 남기고, 새 결정은 새 번호로 분리한다.

---

## 실행

```bash
docker compose up -d
./gradlew test
```

테스트가 전부 통과하면 이 저장소가 주장하는 것이 증명된 것이다.
