-- Versioned readiness policy. A version is immutable once created; changing a requirement means
-- publishing a new version (audited). Every quiz attempt and readiness decision records the version
-- it was made under. Version 1 holds the initial policy approved on 2026-10-31 (decision D4).
CREATE TABLE readiness_policies (
    version                       INTEGER      PRIMARY KEY,
    module_pass_mark_pct          INTEGER      NOT NULL CHECK (module_pass_mark_pct BETWEEN 50 AND 100),
    final_pass_mark_pct           INTEGER      NOT NULL CHECK (final_pass_mark_pct BETWEEN 50 AND 100),
    final_retake_cooldown_hours   INTEGER      NOT NULL CHECK (final_retake_cooldown_hours >= 0),
    max_failed_final_attempts     INTEGER      NOT NULL CHECK (max_failed_final_attempts >= 1),
    qualification_validity_days   INTEGER      NOT NULL CHECK (qualification_validity_days >= 1),
    required_exercises            VARCHAR(500) NOT NULL,
    acknowledgement_version       INTEGER      NOT NULL,
    requires_requalification      BOOLEAN      NOT NULL DEFAULT FALSE,
    effective_from                TIMESTAMP WITH TIME ZONE NOT NULL,
    created_by                    BIGINT,
    created_at                    TIMESTAMP WITH TIME ZONE NOT NULL,
    notes                         VARCHAR(500) NOT NULL
);

INSERT INTO readiness_policies (version, module_pass_mark_pct, final_pass_mark_pct, final_retake_cooldown_hours,
        max_failed_final_attempts, qualification_validity_days, required_exercises, acknowledgement_version,
        requires_requalification, effective_from, created_by, created_at, notes)
VALUES (1, 80, 85, 24, 3, 365, 'market-order,limit-order-cancel,stop-loss,position-sizing', 1, FALSE,
        TIMESTAMP WITH TIME ZONE '2026-10-31 00:00:00+00', NULL, TIMESTAMP WITH TIME ZONE '2026-10-31 00:00:00+00',
        'Initial policy (D4): 80% module quizzes, 85% final assessment, 24 h retake cooldown, review after 3 failed final attempts, 12-month validity.');
