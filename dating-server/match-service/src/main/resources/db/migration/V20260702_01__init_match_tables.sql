-- match-service initial schema (luan)
-- All tables use TIMESTAMPTZ (UTC), snowflake IDs, and soft-delete (deleted flag).

-- ──────────────────────────────────────────────
-- Swipe history: every swipe action recorded
-- ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS user_swipe_history (
    id                  BIGINT PRIMARY KEY,
    user_id             BIGINT NOT NULL,
    target_user_id      BIGINT NOT NULL,
    target_user_type    SMALLINT NOT NULL,         -- 1=BH, 2=DH
    direction           SMALLINT NOT NULL,         -- 1=LEFT, 2=RIGHT, 3=SUPER_HI
    swiped_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted             BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (user_id, target_user_id)
);
CREATE INDEX IF NOT EXISTS idx_swipe_user_time ON user_swipe_history (user_id, swiped_at DESC);
CREATE INDEX IF NOT EXISTS idx_swipe_target_dir ON user_swipe_history (target_user_id, direction) WHERE deleted = false;

-- ──────────────────────────────────────────────
-- Match relationship: (low, high) pair unique
-- ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS match (
    id              BIGINT PRIMARY KEY,
    user_id_low     BIGINT NOT NULL,                 -- min(uid1, uid2)
    user_id_high    BIGINT NOT NULL,                 -- max(uid1, uid2)
    matched_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    source          VARCHAR(30) NOT NULL,            -- SWIPE_MATCH | SWIPE_SUPER_HI
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted         BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (user_id_low, user_id_high)
);
CREATE INDEX IF NOT EXISTS idx_match_low_time  ON match (user_id_low,  matched_at DESC);
CREATE INDEX IF NOT EXISTS idx_match_high_time ON match (user_id_high, matched_at DESC);

-- ──────────────────────────────────────────────
-- Match outbox: async side effects retry
-- ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS match_outbox (
    id            BIGINT PRIMARY KEY,
    match_id      BIGINT NOT NULL,
    action        VARCHAR(40) NOT NULL,              -- ENSURE_CONVERSATION | SYSTEM_MSG | DH_OPENING
    payload_json  JSONB NOT NULL,
    attempts      INT NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ NOT NULL,
    status        VARCHAR(20) NOT NULL,              -- PENDING | DONE | DEAD
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted       BOOLEAN NOT NULL DEFAULT false
);
CREATE INDEX IF NOT EXISTS idx_outbox_status_retry ON match_outbox (status, next_retry_at) WHERE deleted = false;

-- ──────────────────────────────────────────────
-- Like record: who liked me (unidirectional, pre-match)
-- ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS like_record (
    id              BIGINT PRIMARY KEY,
    from_user_id    BIGINT NOT NULL,
    to_user_id      BIGINT NOT NULL,
    from_user_type  SMALLINT NOT NULL,               -- 1=BH 2=DH
    source          SMALLINT NOT NULL,               -- 1=SWIPE_RIGHT 2=DH_PLAN_ONLINE 3=DH_PLAN_OFFLINE
    like_content    VARCHAR(200),                    -- DH task content; NULL for real swipe
    liked_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted         BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (from_user_id, to_user_id)
);
CREATE INDEX IF NOT EXISTS idx_like_to_user_time ON like_record (to_user_id, liked_at DESC) WHERE deleted = false;
CREATE INDEX IF NOT EXISTS idx_like_to_user_type ON like_record (to_user_id, from_user_type, liked_at DESC) WHERE deleted = false;

-- ──────────────────────────────────────────────
-- Visit record: who visited me
-- ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS visit_record (
    id              BIGINT PRIMARY KEY,
    from_user_id    BIGINT NOT NULL,
    to_user_id      BIGINT NOT NULL,
    from_user_type  SMALLINT NOT NULL,
    source          SMALLINT NOT NULL,               -- 1=PROFILE_VIEW 2=DH_PLAN_ONLINE 3=DH_PLAN_OFFLINE
    visit_count     INT NOT NULL DEFAULT 1,
    visited_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted         BOOLEAN NOT NULL DEFAULT false,
    UNIQUE (from_user_id, to_user_id)
);
CREATE INDEX IF NOT EXISTS idx_visit_to_user_time ON visit_record (to_user_id, visited_at DESC) WHERE deleted = false;
CREATE INDEX IF NOT EXISTS idx_visit_to_user_type ON visit_record (to_user_id, from_user_type, visited_at DESC) WHERE deleted = false;

-- ──────────────────────────────────────────────
-- DH interaction task: scheduled DH like/visit jobs
-- ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS dh_interaction_task (
    id              BIGINT PRIMARY KEY,
    from_user_id    BIGINT NOT NULL,                 -- DH user_id (initiator)
    to_user_id      BIGINT NOT NULL,                 -- Real user_id (receiver)
    action          SMALLINT NOT NULL,               -- 1=LIKE 2=VISIT
    scene           SMALLINT NOT NULL,               -- 1=ONLINE 2=OFFLINE
    execute_time    TIMESTAMPTZ NOT NULL,            -- Scheduled execution time
    like_content    VARCHAR(200),                    -- Only for LIKE actions
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_dh_task_execute_time ON dh_interaction_task (execute_time);
CREATE INDEX IF NOT EXISTS idx_dh_task_to_user_scene ON dh_interaction_task (to_user_id, scene);

-- ──────────────────────────────────────────────
-- ShedLock table (for multi-instance job mutex)
-- ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS shedlock (
    name        VARCHAR(64)   PRIMARY KEY,
    lock_until  TIMESTAMPTZ   NOT NULL,
    locked_at   TIMESTAMPTZ   NOT NULL,
    locked_by   VARCHAR(255)  NOT NULL
);
