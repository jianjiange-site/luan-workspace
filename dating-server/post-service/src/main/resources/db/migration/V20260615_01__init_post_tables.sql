-- ─────────────────────────────────────────────
-- post-service initial schema
-- 5 business tables + shedlock
-- All timestamps: TIMESTAMPTZ (UTC per redline §8)
-- ─────────────────────────────────────────────

-- 1. Posts main table
CREATE TABLE IF NOT EXISTS posts (
    id          BIGSERIAL       PRIMARY KEY,
    post_id     BIGINT          UNIQUE NOT NULL,
    user_id     BIGINT          NOT NULL,
    content     VARCHAR(1024)   NOT NULL,
    status      SMALLINT        DEFAULT 1,   -- 0=deleted, 1=normal, 2=reviewing
    deleted     SMALLINT        DEFAULT 0,
    created_at  TIMESTAMPTZ     DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMPTZ     DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_posts_user_created ON posts (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_posts_created_at ON posts (created_at DESC);

-- 2. Post images
CREATE TABLE IF NOT EXISTS post_images (
    post_id     BIGINT          NOT NULL,
    sort_order  SMALLINT        NOT NULL,
    image_key   VARCHAR(128)    NOT NULL,
    created_at  TIMESTAMPTZ     DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (post_id, sort_order)
);

-- 3. Post stats (count base — flushed portion only)
CREATE TABLE IF NOT EXISTS post_stats (
    post_id        BIGINT    PRIMARY KEY,
    like_count     INT       DEFAULT 0,
    comment_count  INT       DEFAULT 0,
    updated_at     TIMESTAMPTZ DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_post_stats_like_count ON post_stats (like_count DESC);
CREATE INDEX IF NOT EXISTS idx_post_stats_comment_count ON post_stats (comment_count DESC);

-- 4. Post likes (idempotent upsert record)
CREATE TABLE IF NOT EXISTS post_likes (
    user_id    BIGINT        NOT NULL,
    post_id    BIGINT        NOT NULL,
    status     SMALLINT      DEFAULT 1,   -- 1=liked, 0=unliked
    created_at TIMESTAMPTZ   DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ   DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, post_id)
);

CREATE INDEX IF NOT EXISTS idx_post_likes_post_status ON post_likes (post_id) WHERE status = 1;

-- 5. Post comments
CREATE TABLE IF NOT EXISTS post_comments (
    id                 BIGSERIAL       PRIMARY KEY,
    comment_id         BIGINT          UNIQUE NOT NULL,
    post_id            BIGINT          NOT NULL,
    user_id            BIGINT          NOT NULL,
    root_id            BIGINT          DEFAULT 0,  -- root comment id (0 for top-level)
    parent_id          BIGINT          DEFAULT 0,  -- direct parent comment id
    reply_to_user_id   BIGINT          DEFAULT 0,  -- the user being replied to
    content            VARCHAR(512)    NOT NULL,
    status             SMALLINT        DEFAULT 1,
    deleted            SMALLINT        DEFAULT 0,
    created_at         TIMESTAMPTZ     DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_comments_post_root_created ON post_comments (post_id, root_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_comments_root_created ON post_comments (root_id, created_at ASC);

-- 6. ShedLock table (multi-instance job mutex)
CREATE TABLE IF NOT EXISTS shedlock (
    name        VARCHAR(64)   PRIMARY KEY,
    lock_until  TIMESTAMPTZ   NOT NULL,
    locked_at   TIMESTAMPTZ   NOT NULL,
    locked_by   VARCHAR(255)  NOT NULL
);
