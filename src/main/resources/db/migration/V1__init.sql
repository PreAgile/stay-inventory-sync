-- ─────────────────────────────────────────────────────────────────────────────
-- V1 — 도메인 모델 8개 테이블
--
-- 이 스키마의 단일 진실 원천은 이 파일이다. Hibernate 는 ddl-auto: validate 로
-- 검증만 한다. 양쪽이 테이블을 만들면 어느 쪽이 진실인지 판정할 수 없다.
--
-- CHECK 제약을 애플리케이션 검증으로 대체하지 않는다. 앱 검증은 읽기와 쓰기
-- 사이에 다른 트랜잭션이 끼어들 수 있다. 경합으로 앱 로직이 뚫려도 DB 가 막는다.
--
-- 근거: docs/01-domain-model.md · ADR-0007 · ADR-0009 · ADR-0010 · README A7·A8
-- ─────────────────────────────────────────────────────────────────────────────

-- ── 1. property ──────────────────────────────────────────────────────────────
CREATE TABLE property (
    id          BIGSERIAL    PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    -- 체크인·체크아웃 날짜는 숙소의 현지 시각으로 해석한다.
    -- UTC 로 저장하면 "3월 1일 재고" 가 숙소에 따라 다른 날을 뜻하게 된다.
    timezone    VARCHAR(64)  NOT NULL DEFAULT 'Asia/Seoul',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ── 2. room_type ─────────────────────────────────────────────────────────────
CREATE TABLE room_type (
    id           BIGSERIAL    PRIMARY KEY,
    property_id  BIGINT       NOT NULL REFERENCES property (id),
    name         VARCHAR(200) NOT NULL,
    capacity     INT          NOT NULL,
    -- 즉시확정이냐 승인형이냐 (ADR-0010 결정 7). 예약이 HELD 다음에
    -- CONFIRMED 로 갈지 PENDING_APPROVAL 로 갈지를 이 값이 가른다.
    booking_mode VARCHAR(16)  NOT NULL DEFAULT 'INSTANT',
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT room_type_capacity_positive CHECK (capacity > 0),
    CONSTRAINT room_type_booking_mode_valid
        CHECK (booking_mode IN ('INSTANT', 'ON_REQUEST'))
);

CREATE INDEX idx_room_type_property ON room_type (property_id);

-- ── 3. daily_inventory ───────────────────────────────────────────────────────
-- (룸타입 × 날짜) 격자. sold 는 성능 최적화가 아니라 경합의 직렬화 지점이다 --
-- 카운터가 있는 행을 잠가야 "잔여 1개에 100명 동시 요청" 을 직렬화할 수 있다.
CREATE TABLE daily_inventory (
    room_type_id      BIGINT      NOT NULL REFERENCES room_type (id),
    stay_date         DATE        NOT NULL,

    -- total 컬럼은 없다. total = physical_total + overbooking_limit 이며 계산값이다.
    -- 합산값 하나만 두면 셋을 동시에 잃는다 (ADR-0007):
    --   지표(무엇과 비교해 차단했는가) · 경고(한도에 얼마나 가까운가) ·
    --   되돌림(overbooking_limit = 0 이면 현재 동작과 수학적으로 동일하다)
    physical_total    INT         NOT NULL,
    overbooking_limit INT         NOT NULL DEFAULT 0,
    sold              INT         NOT NULL DEFAULT 0,

    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    PRIMARY KEY (room_type_id, stay_date),

    -- INV-1. total 이 컬럼이 아니므로 두 컬럼의 합으로 쓴다.
    -- CHECK (sold <= total) 로 쓰면 컬럼이 없어 마이그레이션이 실패한다.
    CONSTRAINT daily_inventory_sold_within_total
        CHECK (sold >= 0 AND sold <= physical_total + overbooking_limit),
    CONSTRAINT daily_inventory_totals_non_negative
        CHECK (physical_total >= 0 AND overbooking_limit >= 0)
);

-- ── 4. reservation ───────────────────────────────────────────────────────────
CREATE TABLE reservation (
    id                     BIGSERIAL    PRIMARY KEY,
    room_type_id           BIGINT       NOT NULL REFERENCES room_type (id),

    -- 반개구간 [check_in, check_out). 체크아웃 날짜는 점유하지 않는다.
    check_in               DATE         NOT NULL,
    check_out              DATE         NOT NULL,

    status                 VARCHAR(24)  NOT NULL,

    -- A7. 한 예약이 여러 객실을 잡을 수 있다. sold 증감 단위가 1 이 아니라 이 값이다.
    room_count             INT          NOT NULL DEFAULT 1,

    channel                VARCHAR(32)  NOT NULL,
    -- DIRECT 채널은 내부 생성 UUID 를 채워 NULL 을 피한다.
    -- NULL 을 허용하면 아래 UNIQUE 가 DIRECT 예약에 대해 아무것도 막지 못한다 --
    -- inbound_message 의 sequence_key 와 같은 함정이다.
    channel_reservation_id VARCHAR(128) NOT NULL,

    guest_name             VARCHAR(200) NOT NULL,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- 중복 웹훅 최종 방어선. 애플리케이션 멱등이 경합으로 뚫려도 DB 가 막는다.
    CONSTRAINT reservation_channel_id_unique
        UNIQUE (channel, channel_reservation_id),

    -- inventory_hold 의 복합 FK 가 참조할 대상. 데이터 유일성을 위한 것이 아니라
    -- (id 가 이미 PK 다) 선점이 예약과 같은 룸타입·수량을 갖도록 묶기 위한 것이다.
    -- 이것이 없으면 룸타입 A 예약에 룸타입 B 재고를 선점할 수 있다.
    CONSTRAINT reservation_hold_target_unique
        UNIQUE (id, room_type_id, room_count),

    -- INV-3
    CONSTRAINT reservation_stay_range_valid CHECK (check_in < check_out),
    -- room_count 가 음수면 INV-4 를 통과하면서 INV-1 을 깬다.
    CONSTRAINT reservation_room_count_positive CHECK (room_count > 0),

    CONSTRAINT reservation_status_valid CHECK (
        status IN (
            'HELD', 'PENDING_APPROVAL',
            'CONFIRMED', 'CHECKED_IN', 'CHECKED_OUT', 'TERMINATED',
            'EXPIRED', 'REJECTED', 'CANCELED'
        )
    )
);

CREATE INDEX idx_reservation_room_type_stay ON reservation (room_type_id, check_in, check_out);
CREATE INDEX idx_reservation_status ON reservation (status);

-- ── 5. inventory_hold ────────────────────────────────────────────────────────
-- 확정 전 재고 선점 (ADR-0010). 결제 중에 방이 팔리는 것을 막는다.
CREATE TABLE inventory_hold (
    id             BIGSERIAL   PRIMARY KEY,
    room_type_id   BIGINT      NOT NULL,
    stay_date      DATE        NOT NULL,
    -- 다일 선점을 묶는 키를 겸한다. 날짜마다 한 행이고 예약 하나에 여러 행이 달린다.
    -- 단독 FK 를 두지 않는다. 아래 복합 FK 가 예약의 존재와 룸타입·수량 일치를
    -- 함께 보장하므로 중복이다.
    reservation_id BIGINT      NOT NULL,
    -- reservation.room_count 와 같은 값. INV-4 증명의 전제다.
    room_count     INT         NOT NULL,

    -- 유효 선점 = expires_at > now() AND released_at IS NULL.
    -- 두 조건이 모두 필요하다. expires_at 만 보면 확정된 예약이 남은 시간 동안
    -- 자기 방을 계속 붙잡아 "확정이 곧 품절을 만드는" 구조가 된다.
    expires_at     TIMESTAMPTZ NOT NULL,
    released_at    TIMESTAMPTZ,

    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT inventory_hold_daily_inventory_fk
        FOREIGN KEY (room_type_id, stay_date)
        REFERENCES daily_inventory (room_type_id, stay_date),

    -- reservation_id 만 참조하면 예약의 **존재**만 보장된다. 그 예약이 같은
    -- 룸타입인지, 같은 수량인지는 보장되지 않는다. 그러면 룸타입 A 예약에
    -- 룸타입 B 재고를 선점할 수 있고, INV-4 는 B 격자로 계산하는데 확정 시
    -- 차감은 A 격자로 간다 -- 가용 재고를 잘못 약속하는 구조가 된다.
    --
    -- 세 값을 한 제약으로 묶는다. room_count 가 예약과 다른 선점도 같이 막힌다.
    -- room_count 를 여기서 지우고 조인으로 읽는 안도 있지만, 유효 선점 조회가
    -- 이 프로젝트에서 가장 잦은 읽기이므로 비정규화를 유지하고 DB 가 일치를 강제한다.
    CONSTRAINT inventory_hold_reservation_match_fk
        FOREIGN KEY (reservation_id, room_type_id, room_count)
        REFERENCES reservation (id, room_type_id, room_count),

    CONSTRAINT inventory_hold_room_count_positive CHECK (room_count > 0)
);

-- 부분 인덱스. 해제된 선점은 다시 조회되지 않으므로 인덱스에서 뺀다.
-- 유효 선점 조회가 이 프로젝트에서 가장 잦은 읽기다.
CREATE INDEX idx_inventory_hold_active
    ON inventory_hold (room_type_id, stay_date)
    WHERE released_at IS NULL;

CREATE INDEX idx_inventory_hold_reservation ON inventory_hold (reservation_id);

-- 같은 예약·같은 날짜에 활성 선점은 하나뿐이다.
--
-- 이 제약이 없으면 재시도된 선점 요청이나 동시 요청 두 건이 행을 둘 만들고,
-- INV-4 의 유효 선점 합계가 room_count 를 두 번 센다 -- 3객실 예약이 6으로
-- 읽혀 남은 재고를 실제보다 적게 판정한다. 조용한 기회손실이다.
--
-- 부분 인덱스인 이유: released_at 이 채워진 과거 선점은 이력으로 남아야 하고,
-- 해제 후 같은 날짜를 다시 선점하는 것은 정상 경로다.
CREATE UNIQUE INDEX uq_inventory_hold_one_active_per_stay_date
    ON inventory_hold (reservation_id, stay_date)
    WHERE released_at IS NULL;

-- ── 6. outbox_event ──────────────────────────────────────────────────────────
-- 나갈 통보 (ADR-0003). 도메인 트랜잭션 안에서 "보내야 한다는 사실" 을 같이 쓴다.
CREATE TABLE outbox_event (
    id              BIGSERIAL    PRIMARY KEY,
    -- aggregate_id 에 FK 를 걸지 않는다. aggregate_type 에 따라 대상 테이블이
    -- 달라지는 다형 참조이므로 한 테이블로 고정할 수 없다.
    aggregate_type  VARCHAR(32)  NOT NULL,
    aggregate_id    BIGINT       NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    payload         JSONB        NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    retry_count     INT          NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,

    CONSTRAINT outbox_event_status_valid
        CHECK (status IN ('PENDING', 'PUBLISHED', 'DEAD')),
    CONSTRAINT outbox_event_retry_count_non_negative CHECK (retry_count >= 0)
);

-- 릴레이 폴링 경로. FOR UPDATE SKIP LOCKED 가 붙을 자리다 (#8).
CREATE INDEX idx_outbox_event_pending
    ON outbox_event (status, next_attempt_at);

-- ── 7. inbound_message ───────────────────────────────────────────────────────
-- 들어온 알림 보관 (ADR-0009 · Inbox). 다른 테이블로 가는 FK 가 없다.
-- 참조 대상이 다른 테이블이고(BOOKING -> reservation, POLICY -> channel_policy)
-- 개수도 다르며(0~1 건 vs 0~N 건), 에코와 해석 실패는 끝까지 대상이 없다.
CREATE TABLE inbound_message (
    id            BIGSERIAL    PRIMARY KEY,
    channel       VARCHAR(32)  NOT NULL,
    kind          VARCHAR(32)  NOT NULL,
    external_id   VARCHAR(128) NOT NULL,
    -- NULL 을 허용한다. 순서키를 주지 않는 채널이 있고, 그 사실을 빈 문자열로
    -- 뭉개면 "주지 않았다" 와 "빈 문자열을 주었다" 를 구분할 수 없다 (#9 가 쓴다).
    sequence_key  VARCHAR(128),
    -- 받은 그대로. 해석하지 않는다. 해석에서 실패해도 받은 사실은 남아야 한다.
    payload       JSONB        NOT NULL,
    status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempt_count INT          NOT NULL DEFAULT 0,
    received_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at  TIMESTAMPTZ,

    -- NULLS NOT DISTINCT 가 이 테이블의 핵심 한 줄이다. PostgreSQL 15 이상.
    --
    -- 빼면 제약이 아무것도 막지 않는다. sequence_key 가 NULL 인 행은 서로
    -- 중복으로 판정되지 않기 때문이다(기본은 NULLS DISTINCT, NULL = NULL 이 참이 아니다).
    -- 순서키를 주는 채널로 테스트하면 통과하므로 조용히 뚫린다.
    CONSTRAINT inbound_message_dedup_unique
        UNIQUE NULLS NOT DISTINCT (channel, external_id, sequence_key),

    CONSTRAINT inbound_message_kind_valid CHECK (kind IN ('BOOKING', 'POLICY')),
    CONSTRAINT inbound_message_status_valid
        CHECK (status IN ('PENDING', 'PROCESSED', 'IGNORED', 'DEAD')),
    CONSTRAINT inbound_message_attempt_count_non_negative CHECK (attempt_count >= 0)
);

CREATE INDEX idx_inbound_message_pending
    ON inbound_message (status, received_at)
    WHERE status = 'PENDING';

-- ── 8. channel_policy ────────────────────────────────────────────────────────
-- 채널별 노출 규칙 장부 (ADR-0009). 채널이 바꾼 것이 재고인지 정책인지 가른다.
CREATE TABLE channel_policy (
    -- room_type 에는 FK 를 건다. 없는 룸타입의 정책은 의미가 없다.
    room_type_id BIGINT      NOT NULL REFERENCES room_type (id),
    stay_date    DATE        NOT NULL,
    channel      VARCHAR(32) NOT NULL,
    kind         VARCHAR(16) NOT NULL,
    value        INT,
    -- 정책의 출처를 데이터로 둔다. "이 값은 우리가 정했나 현장이 정했나" 를
    -- 나중에 물을 수 있어야 한다.
    source       VARCHAR(16) NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- kind 까지 4개다. 알림 하나가 kind 여러 개를 실어 오므로
    -- (룸타입, 날짜, 채널) 세 개로는 행이 특정되지 않는다.
    PRIMARY KEY (room_type_id, stay_date, channel, kind),

    -- (room_type_id, stay_date) 로 daily_inventory 에 FK 를 걸지 않는다.
    -- 걸면 재고 행이 아직 없는 미래 날짜에 노출 상한을 미리 설정하는 것이 막히고,
    -- 그것은 캡형 운영(#36)에서 실제로 필요한 동작이다. 격자를 공유할 뿐이다.

    CONSTRAINT channel_policy_kind_valid
        CHECK (kind IN ('CLOSED', 'CAP', 'OFFSET')),
    CONSTRAINT channel_policy_source_valid
        CHECK (source IN ('OURS', 'CHANNEL')),
    -- CLOSED 는 값이 필요 없고, CAP 과 OFFSET 은 값이 있어야 한다.
    -- 값 없는 CAP 을 허용하면 "상한이 없음" 과 "상한 0" 을 구분할 수 없다.
    CONSTRAINT channel_policy_value_required_by_kind CHECK (
        (kind = 'CLOSED' AND value IS NULL)
        OR (kind IN ('CAP', 'OFFSET') AND value IS NOT NULL)
    )
);

CREATE INDEX idx_channel_policy_grid ON channel_policy (room_type_id, stay_date);
