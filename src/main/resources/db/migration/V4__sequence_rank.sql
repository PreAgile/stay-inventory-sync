-- ─────────────────────────────────────────────────────────────────────────────
-- V4 — 순서키의 정규화 값 (ADR-0013 · #72)
--
-- sequence_key 는 VARCHAR 이고 워커의 정렬이 문자열 정렬이었다. 그래서 숫자
-- 리비전 '10' 이 '2' 보다 먼저 처리됐고, 취소가 생성보다 앞서 IGNORED 된 뒤
-- 생성이 확정되어 **최신 취소가 사라졌다.**
--
-- 원본은 그대로 둔다. 멱등 판정(UNIQUE NULLS NOT DISTINCT)도 원본에 걸린 채로
-- 유지한다 -- 정규화가 두 값을 같은 rank 로 뭉개도 서로 다른 알림이라는 사실은
-- 남아야 한다.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE inbound_message
    -- 어댑터가 정규화한 비교 가능한 값. NULL 은 "순서를 복원할 수 없다" 는 뜻이고
    -- 그 사실 자체가 설계 정보다 -- 그 채널은 drift 검출에 더 의존해야 한다.
    ADD COLUMN sequence_rank BIGINT,

    -- 알림의 사건 종류. kind(BOOKING|POLICY) 아래의 세부 사건이다.
    --
    -- 묘비 판정이 "이 예약에 더 높은 rank 의 취소가 있나" 를 묻는데, 그것을
    -- payload LIKE 로 찾으면 게스트 이름에 같은 문자열이 있을 때 오탐한다.
    -- 기록 시점에 이미 타입이 있으므로 컬럼으로 둔다 -- payload 를 해석하는 것이
    -- 아니라 받은 사실의 분류를 적는 것이다.
    ADD COLUMN event_type VARCHAR(32);

-- ── 멱등 키에 event_type 을 넣는다 ──────────────────────────────────────────
--
-- 순서키를 주지 않는 채널에서 **같은 예약의 취소가 중복으로 버려졌다.**
-- 키가 (channel, external_id, sequence_key) 이고 sequence_key 가 NULL 이면
-- 생성과 취소가 같은 키를 갖는다 -- NULLS NOT DISTINCT 가 둘을 같은 알림으로 본다.
--
--   INSERT 생성 (CH, R-1, NULL)  -> 성공
--   INSERT 취소 (CH, R-1, NULL)  -> 중복으로 거부. 취소가 사라진다
--
-- 그리고 조용하다. recorder 가 Duplicate 로 흡수해 2xx 를 주므로 채널은
-- 전달됐다고 믿고 다시 보내지 않는다. **취소가 영구히 유실된다.**
--
-- event_type 을 키에 넣으면 같은 사건의 재전송만 중복으로 판정된다.
-- DROP INDEX 를 쓰지 않는다. 제약이 그 인덱스를 소유하고 있어서
-- "constraint ... requires it" 으로 거부된다. 제약을 떨어뜨리면 인덱스도 같이 간다.
ALTER TABLE inbound_message DROP CONSTRAINT inbound_message_dedup_unique;
ALTER TABLE inbound_message
    ADD CONSTRAINT inbound_message_dedup_unique
    UNIQUE NULLS NOT DISTINCT (channel, external_id, event_type, sequence_key);

-- 정렬 경로. (external_id, sequence_rank) 로 훑으므로 그 순서로 잡는다.
-- status 를 앞에 두는 이유는 폴링이 PENDING 만 보기 때문이다.
CREATE INDEX idx_inbound_message_order
    ON inbound_message (status, external_id, sequence_rank, id);

-- 묘비 조회 경로. "이 예약에 더 높은 rank 의 취소가 이미 있나" 를 묻는다.
CREATE INDEX idx_inbound_message_tombstone
    ON inbound_message (channel, external_id, event_type, sequence_rank);
