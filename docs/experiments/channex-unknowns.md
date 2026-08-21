# 실측 계획 — 공개 문서에 없는 3가지

`docs/07-reconciliation.md` 의 U1~U3 을 staging 환경에서 확정한다.

**이 세 가지는 보장형 하이브리드(ADR-0001)의 성립 여부를 가른다.**
문서에 없는 것을 추측으로 채우고 설계하면, 설계가 아니라 가정이 된다.

---

## 왜 실측인가

공개 문서에서 확인된 것과 확인되지 않은 것을 먼저 갈라 둔다.

| 확인됨 | 근거 |
|---|---|
| availability 는 룸타입 단위 절대값, 채널별 값을 받지 않는다 | ARI 문서 |
| 채널별 차등은 `close_out` / `availability_offset` / `max_availability` 규칙만 | Availability Rules 문서 |
| 프로퍼티당 분당 availability 10회, restrictions·price 10회 | Rate Limits 문서 |
| feed 는 ack 하지 않은 리비전만 반환하며 ack 후 사라진다 | Bookings 문서 |
| 웹훅 payload 에 `user_id` 가 있고 자기 변경 무시 용도다 | Webhook 문서 |

| 확인 안 됨 | 왜 필요한가 |
|---|---|
| **U1** `GET /availability` · 규칙 갱신이 ARI 리밋에 포함되는가 | 읽기가 쓰기 예산을 잠식하면 drift 검출 주기를 정할 수 없다 |
| **U2** 같은 룸타입에 규칙이 여러 개 걸릴 때의 우선순위 | 캡형과 보장형을 동시에 쓸 수 있는지가 여기 달렸다 |
| **U3** 규칙의 날짜 granularity 와 갱신 비용 | 날짜별 배정 잔여를 규칙으로 표현할 때 규칙 수가 폭발하는지 |

---

## 선행 조건

**계정과 API 키는 저장소 소유자가 직접 만든다.**
staging 은 자체 가입이 가능하고, 키는 admin 의 user profile 에서 발급한다.

```
BASE      https://staging.channex.io
AUTH      user-api-key: <발급받은 키>
```

키를 저장소에 커밋하지 않는다. 환경 변수로만 전달한다.

```bash
export CHANNEX_API_KEY=...
export CHANNEX_PROPERTY_ID=...
export CHANNEX_ROOM_TYPE_ID=...
export CHANNEX_CHANNEL_ID=...
```

---

## U1. 읽기와 규칙 갱신이 리밋을 소비하는가

### 가설

리밋 문서는 `Availability Requests` 라고만 적고 어느 엔드포인트가 포함되는지 열거하지 않는다.
**세 가지 경우가 가능하다.**

| 경우 | 함의 |
|---|---|
| GET 은 리밋 밖 | drift 검출 주기를 자유롭게 정할 수 있다 |
| GET 이 availability 10회를 공유 | 읽기 1회당 쓰기 1회를 잃는다. 주기 산정에 읽기를 넣어야 한다 |
| GET 에 별도 리밋 | 별도 예산으로 관리 |

### 절차

**1단계 (읽기만).** `GET /availability` 를 1분 안에 15회 호출해 429 발생 지점을 기록한다.
10회에서 막히면 availability 예산을 공유하거나 자체 리밋이 10인 것이다.

**2단계 (쓰기 포함).** 읽기로 예산을 소진시킨 뒤 `POST /availability` 를 1회 시도한다.

```
GET × 10  →  POST × 1
  POST 가 429  →  같은 예산을 공유한다        (최악의 경우)
  POST 가 200  →  읽기는 별도 예산이다        (원하는 결과)
```

**3단계.** 규칙 생성·수정을 1분 안에 12회 반복해 429 지점을 본다.
규칙이 availability 예산을 공유하면 보장형의 갱신 빈도가 곧바로 제약된다.

### 판정 기준

```
U1 통과 조건: GET 과 규칙 갱신이 POST /availability 예산을 잠식하지 않는다
U1 실패 시  : drift 검출 주기와 배정 갱신 빈도를 쓰기 예산에서 차감해 재산정한다
              → 04-capacity-and-limits.md 의 예산 표를 개정한다
```

---

## U2. 규칙이 여러 개 걸릴 때의 우선순위

### 가설

보장형 하이브리드는 **배정 채널 외의 채널에 `availability_offset`** 을 건다.
동시에 취소율 높은 채널에는 **`max_availability`** 캡을 걸고 싶다.

같은 (룸타입, 채널) 에 두 규칙이 걸리면 어떻게 되는가.

```
전체 10,  offset 2,  max 5   →  결과는 5 인가 8 인가, 아니면 min(8,5)=5 인가
                              →  아니면 API 가 애초에 거부하는가
```

### 절차

1. 같은 룸타입·채널에 `availability_offset=2` 규칙을 만든다
2. 같은 대상에 `max_availability=5` 규칙을 추가로 만든다
3. **생성이 거부되는지** 먼저 본다 — 거부되면 동시 사용이 불가능하다는 답이다
4. 생성되면 채널 매핑의 실제 노출값을 확인한다

### 관찰의 한계

**실제 채널에 무엇이 보이는지는 staging 에서 완전히 관측되지 않는다.**
실제 OTA 연결 없이는 최종 노출값을 볼 수 없다.

따라서 U2 의 1차 답은 **"두 규칙이 공존 가능한가"** 까지다.
공존이 되면 우선순위는 Channex 지원에 문의해 문서화한다 — 추측하지 않는다.

### 판정 기준

```
공존 불가  →  캡형과 보장형은 상호 배타. ADR-0001 에 명시한다
공존 가능  →  우선순위 확인까지는 보장형을 착수하지 않는다
```

---

## U3. 규칙의 날짜 granularity 와 갱신 비용

### 가설

규칙의 범위는 `start_date` · `end_date` · `days`(요일) 다.
그런데 배정 잔여는 **날짜마다 다르다.**

```
9/01  배정 3 중 1 판매  →  offset 2
9/02  배정 3 중 3 판매  →  offset 0
9/03  배정 3 중 0 판매  →  offset 3
```

날짜별로 값이 다르면 규칙이 날짜마다 하나씩 필요한가.

### 절차

1. 단일 날짜 범위(`start_date == end_date`) 규칙이 생성되는지 확인
2. 30일 구간에 날짜별로 다른 offset 을 표현하려면 규칙이 몇 개 필요한지 센다
3. 값이 같은 연속 구간을 병합했을 때(run-length) 규칙 수가 실제로 줄어드는지 확인
4. 규칙 갱신이 PATCH 인지 삭제+생성인지, 그 호출 수를 센다

### 판정 기준

```
날짜당 규칙 1개가 필요하고 갱신이 리밋을 소비한다면
  → 보장형은 "배정 기간을 짧게, 배정 채널을 하나로" 제한하지 않으면 성립하지 않는다
  → 이 제약을 ADR-0001 의 보장형 절에 명시한다
```

---

## 실행

```bash
export CHANNEX_API_KEY=...  CHANNEX_PROPERTY_ID=...
./scripts/channex-probe.sh u1-read          # 읽기만. 안전
./scripts/channex-probe.sh u1-write --confirm
./scripts/channex-probe.sh u2 --confirm
./scripts/channex-probe.sh u3 --confirm
./scripts/channex-probe.sh cleanup --confirm
```

쓰기가 필요한 항목은 `--confirm` 없이는 요청 내용만 출력하고 멈춘다.
생성한 규칙 ID 는 `.probe-created` 에 기록되며 `cleanup` 이 이를 지운다.

---

## 결과 기록

실측이 끝나면 아래 표를 채우고, 영향받는 문서를 함께 개정한다.

| 항목 | 결과 | 측정일 | 영향받는 문서 |
|---|---|---|---|
| U1 GET 이 리밋을 소비하는가 | *미측정* | | `04-capacity-and-limits.md`, `07-reconciliation.md` |
| U1 규칙 갱신이 리밋을 소비하는가 | *미측정* | | `04-capacity-and-limits.md` |
| U2 규칙 공존 가능 여부 | *미측정* | | `adr/0001-pooled-inventory.md` |
| U2 우선순위 | *미측정* | | `adr/0001-pooled-inventory.md` |
| U3 날짜당 규칙 수 | *미측정* | | `adr/0001-pooled-inventory.md`, `07-reconciliation.md` |

**측정하지 않은 항목을 근거로 보장형을 착수하지 않는다.**
이것이 이 문서의 존재 이유다.

---

## 출처

- [API Reference | Channex.io](https://docs.channex.io/api-v.1-documentation/api-reference) — `user-api-key` 헤더, staging 자체 가입
- [API Rate Limits | Channex.io](https://docs.channex.io/api-v.1-documentation/rate-limits) — 분당 10 / 10, 429 시 1분 정지 권고
- [Availability Rules Collection | Channex.io](https://docs.channex.io/api-v.1-documentation/availability-rules-collection) — 규칙 필드와 범위
