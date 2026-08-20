# 백로그

## 규칙

1. **금요일에 확정했고 이후 변경하지 않는다.** 마감 직전의 즉흥적 기능 추가를 막기 위함이다.
2. **위에서부터만 착수한다.** 순서를 건너뛰지 않는다.
3. **차단선: 토요일 23:00에 안정성 테스트 4종이 통과하지 않으면 전부 폐기.**

---

## Tier 1

### B1. 채널 재고 diff 리포트

내부 `daily_inventory`와 각 어댑터가 보고한 채널 재고를 비교해 불일치 목록을 반환한다.

- `GET /ops/inventory-diff?propertyId=&from=&to=`
- 응답: `[{roomTypeId, stayDate, internal, channel, delta}]`
- **하지 않음**: 스케줄러, 알림, 자동 보정

*이유: 불일치는 결국 발생한다. 시스템이 자동으로 고치려 드는 것보다,
사람이 볼 수 있게 만드는 것이 먼저다. 자동 보정은 원인을 모르는 상태에서 위험하다.*

*Tier 0 이후에 두는 이유: 이 리포트를 먼저 만들면 "눈으로 하던 대조를 화면으로 하게 됐다"에서
끝난다. 확인 업무의 형태만 바뀌고 총량은 그대로다.
정합성이 구조적으로 보장된 뒤에야 이 리포트는 일상 업무가 아니라 예외 감지 장치가 된다.
평소에는 비어 있어야 정상이고, 뭔가 뜨면 진짜 이상이 생긴 것이다.
`docs/00-problem-definition.md`의 "확인 업무라는 신호" 참고.*

### B2. Outbox DLQ + 수동 재처리

- `retry_count > 5` → `status = DEAD`
- `GET /ops/outbox/dead` — 영구 실패 목록
- `POST /ops/outbox/{id}/retry` — 수동 재투입
- **하지 않음**: 자동 재처리, 알림 연동

---

## Tier 2

### B3. 릴레이 `FOR UPDATE SKIP LOCKED`

다중 인스턴스에서 동일 이벤트를 중복 처리하지 않도록 폴링 쿼리를 변경한다.

```sql
SELECT * FROM outbox_event
WHERE status = 'PENDING' AND next_attempt_at <= now()
ORDER BY id
LIMIT 100
FOR UPDATE SKIP LOCKED
```

*값어치: 구현은 한 줄이지만 다중 인스턴스 안전성을 확보한다. 비용 대비 효과가 가장 높은 항목.*

### B4. 배치 내 동일 키 병합

동일 `(room_type_id, stay_date)`에 대한 변경이 한 배치에 여러 개 있으면
마지막 값만 전송한다. 레이트 리밋 소비를 줄인다.

### B5. 지표 2종

- `overbooking_prevented_total` — 재고 부족으로 거절된 요청 수
- `outbox_publish_lag_seconds` — `created_at`부터 `published_at`까지의 분포

*Micrometer를 붙이지 않고 단순 집계 API로 노출한다. 관측 인프라가 없는 환경에서
Micrometer는 의존성만 늘리고 아무것도 보여주지 않는다.*

---

## Tier 3 — 구현하지 않고 문서로만

| 항목 | 문서 위치 |
|---|---|
| Kafka 도입 조건과 마이그레이션 경로 | ADR-0004 |
| Debezium CDC 기반 릴레이 | ADR-0004 |
| 채널별 토큰버킷 스로틀러 | 04-capacity-and-limits.md |
| 개별 호실 배정 | 별개 문제 축 — 다루지 않음 |
