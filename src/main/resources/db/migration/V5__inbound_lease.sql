-- ─────────────────────────────────────────────────────────────────────────────
-- V5 — Inbox 워커의 임대 (#66)
--
-- 릴레이에는 집기-임대가 있고 Inbox 워커에는 없었다. 그 비대칭에 근거가 없었다.
--
-- 없어도 중복 **처리**는 막힌다 -- 조기 반환과 reservation 의 UNIQUE 가 막는다.
-- 막히지 않는 것은 중복 **시도**이고, 그때 한쪽 트랜잭션은 예외로 롤백된 뒤
-- runCatching 이 삼킨다. 즉 "안전하지만 조용히 낭비한다".
--
-- 낭비 자체보다 나쁜 것은 **릴레이와 다른 규율을 쓰는 것**이다. 다음 사람이
-- 어느 쪽을 표준으로 볼지 알 수 없다. 저장소가 이미 배운 패턴을 적용한다.
-- ─────────────────────────────────────────────────────────────────────────────

ALTER TABLE inbound_message
    -- 미래면 다른 인스턴스가 처리 중이라는 뜻이다. 릴레이의 next_attempt_at 과
    -- 같은 역할이지만 컬럼을 따로 두는 이유는 Inbox 에 재시도 스케줄이 없기
    -- 때문이다 -- 실패한 건은 PENDING 으로 남아 다음 회차에 다시 잡힌다.
    ADD COLUMN leased_until TIMESTAMPTZ;

-- 폴링 경로를 임대 조건까지 포함해 다시 잡는다.
DROP INDEX IF EXISTS idx_inbound_message_pending;
CREATE INDEX idx_inbound_message_claimable
    ON inbound_message (status, leased_until, external_id, sequence_rank, id)
    WHERE status = 'PENDING';
