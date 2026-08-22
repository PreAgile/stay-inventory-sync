-- ─────────────────────────────────────────────────────────────────────────────
-- V2 — Outbox 이벤트에 키와 버전을 붙인다
--
-- 백오프가 존재하는 한 **단일 릴레이 인스턴스에서도 순서가 뒤집힌다.**
--
--   t1  (룸타입A, 3/15) 예약 → 3→2   #1 생성, 발행 실패 → +1분 뒤 재시도
--   t2  (룸타입A, 3/15) 취소 → 2→3   #2 즉시 발행 성공 → 채널 잔여 3  ✓
--   t3  #1 재시도 성공               → 채널 잔여 2  ✗  실제는 3
--
-- 결과는 기회손실, 반대 방향이면 오버부킹 -- 이 저장소가 막겠다고 선언한 실패다.
--
-- 근거: docs/adr/0012-outbox-key-version.md · docs/04-capacity-and-limits.md
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE outbox_event
    -- 재고 통보의 키. daily_inventory 와 같은 격자다.
    --
    -- aggregate_id 를 재사용하지 않는다. 그 컬럼은 다형 참조이고 BIGINT 하나라
    -- (룸타입, 날짜) 복합 키를 담을 수 없다. 담으려고 인코딩하면 그 규칙을
    -- 아는 코드가 릴레이와 생성부 양쪽에 생긴다.
    ADD COLUMN room_type_id BIGINT,
    ADD COLUMN stay_date    DATE,
    -- 같은 키 안에서 단조 증가한다. 전역 순서가 아니다 -- 서로 다른 키끼리는
    -- 순서를 비교할 이유가 없고, 전역 순서를 만들면 그것이 곧 직렬화 지점이 된다.
    ADD COLUMN version      BIGINT;

-- 키가 없는 이벤트도 있을 수 있다(재고 통보가 아닌 것). 다만 **셋 중 일부만
-- 있는 상태는 없다** -- 그러면 릴레이가 "키가 있는데 버전이 없다" 를 만나
-- 판정을 포기하거나 NULL 비교로 조용히 통과시킨다.
ALTER TABLE outbox_event ADD CONSTRAINT outbox_event_key_complete CHECK (
    (room_type_id IS NULL AND stay_date IS NULL AND version IS NULL)
    OR (room_type_id IS NOT NULL AND stay_date IS NOT NULL AND version IS NOT NULL)
);

ALTER TABLE outbox_event ADD CONSTRAINT outbox_event_version_positive
    CHECK (version IS NULL OR version > 0);

-- 낡아서 건너뛴 이벤트를 PUBLISHED 로 적지 않는다.
--
-- 발행한 적이 없는데 발행했다고 적으면 "왜 채널에 안 갔는가" 를 되짚을 수 없고,
-- 지표(#10)에서도 발행량이 부풀려진다. 상태를 따로 둔다.
ALTER TABLE outbox_event DROP CONSTRAINT outbox_event_status_valid;
ALTER TABLE outbox_event ADD CONSTRAINT outbox_event_status_valid
    CHECK (status IN ('PENDING', 'PUBLISHED', 'DEAD', 'SUPERSEDED'));

-- 릴레이가 "이 키의 더 큰 버전이 이미 나갔는가" 를 묻는 경로.
CREATE INDEX idx_outbox_event_key_version
    ON outbox_event (room_type_id, stay_date, version)
    WHERE room_type_id IS NOT NULL;
