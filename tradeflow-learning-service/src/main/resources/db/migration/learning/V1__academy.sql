-- Trading Academy: per-user progress and quiz attempts. Content itself is versioned in Git
-- (src/main/resources/academy) and loaded read-only; rows reference it by slug/key and record
-- the content version and question hashes, so results stay interpretable after content changes.

CREATE TABLE lesson_progress (
    user_id         BIGINT       NOT NULL,
    module_slug     VARCHAR(80)  NOT NULL,
    lesson_slug     VARCHAR(80)  NOT NULL,
    content_version INTEGER      NOT NULL,
    lesson_hash     VARCHAR(64)  NOT NULL,
    completed_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, module_slug, lesson_slug)
);

-- quiz_key: a module slug, or 'final' for the final readiness assessment.
CREATE TABLE quiz_attempts (
    id               UUID         PRIMARY KEY,
    user_id          BIGINT       NOT NULL,
    quiz_key         VARCHAR(80)  NOT NULL,
    content_version  INTEGER      NOT NULL,
    policy_version   INTEGER,
    pass_mark_pct    INTEGER      NOT NULL,
    started_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    submitted_at     TIMESTAMP WITH TIME ZONE,
    question_count   INTEGER      NOT NULL,
    correct_count    INTEGER,
    score_pct        INTEGER,
    passed           BOOLEAN,
    version          BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_quiz_attempts_user_quiz ON quiz_attempts (user_id, quiz_key, started_at);

CREATE TABLE quiz_attempt_questions (
    attempt_id       UUID         NOT NULL REFERENCES quiz_attempts (id),
    position         INTEGER      NOT NULL,
    question_key     VARCHAR(80)  NOT NULL,
    question_hash    VARCHAR(64)  NOT NULL,
    option_order     VARCHAR(40)  NOT NULL,
    selected_keys    VARCHAR(40),
    correct          BOOLEAN,
    PRIMARY KEY (attempt_id, position)
);
