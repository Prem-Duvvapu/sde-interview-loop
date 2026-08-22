-- V1: Initial schema for SDE Interview Loop
-- Portable ANSI SQL — no vendor-specific types (no jsonb, no MySQL JSON).
-- JSON data stored as CLOB/TEXT and deserialised in the application layer.
-- Compatible with H2, MySQL, and PostgreSQL.

CREATE TABLE interview_session (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    mode            VARCHAR(20)   NOT NULL,   -- single_module | full_loop
    company_profile_id VARCHAR(64) NOT NULL,
    profile_content_hash VARCHAR(64),
    status          VARCHAR(20)   NOT NULL DEFAULT 'active',  -- active | completed | abandoned
    started_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ended_at        TIMESTAMP
);

CREATE TABLE session_round (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id            BIGINT        NOT NULL,
    ordinal               INT           NOT NULL,
    module_type           VARCHAR(30)   NOT NULL,   -- dsa | lld | hld | cs_fundamentals | java_deep_dive | behavioral
    phase                 VARCHAR(30)   NOT NULL DEFAULT 'PENDING',
    status                VARCHAR(20)   NOT NULL DEFAULT 'pending', -- pending | in_progress | completed | skipped
    interviewer_provider  VARCHAR(30),
    interviewer_model     VARCHAR(64),
    question_slug         VARCHAR(128),
    question_content_hash VARCHAR(64),
    difficulty_target     VARCHAR(20),
    planned_duration_sec  INT,
    actual_duration_sec   INT,
    started_at            TIMESTAMP,
    ended_at              TIMESTAMP,

    CONSTRAINT fk_round_session FOREIGN KEY (session_id) REFERENCES interview_session(id)
);

CREATE TABLE transcript_turn (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    round_id      BIGINT       NOT NULL,
    ordinal       INT          NOT NULL,
    role          VARCHAR(20)  NOT NULL,  -- candidate | interviewer | system
    content       CLOB         NOT NULL,
    content_type  VARCHAR(20)  NOT NULL DEFAULT 'text',
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    latency_ms    INT,

    CONSTRAINT fk_turn_round FOREIGN KEY (round_id) REFERENCES session_round(id)
);

CREATE TABLE artifact_snapshot (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    round_id    BIGINT       NOT NULL,
    turn_id     BIGINT,
    kind        VARCHAR(20)  NOT NULL,  -- code | class_model | diagram | scratch
    language    VARCHAR(30),
    payload     CLOB         NOT NULL,  -- plain text or JSON for diagram graphs
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_artifact_round FOREIGN KEY (round_id) REFERENCES session_round(id),
    CONSTRAINT fk_artifact_turn  FOREIGN KEY (turn_id)  REFERENCES transcript_turn(id)
);

CREATE TABLE signal (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    round_id         BIGINT       NOT NULL,
    turn_id          BIGINT,
    rubric_dimension VARCHAR(64)  NOT NULL,
    score            INT          NOT NULL,  -- 1-5
    confidence       VARCHAR(20),
    evidence         CLOB,

    CONSTRAINT fk_signal_round FOREIGN KEY (round_id) REFERENCES session_round(id),
    CONSTRAINT fk_signal_turn  FOREIGN KEY (turn_id)  REFERENCES transcript_turn(id)
);

CREATE TABLE round_evaluation (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    round_id             BIGINT       NOT NULL,
    rubric_version       VARCHAR(20),
    evaluator_provider   VARCHAR(30),
    evaluator_model      VARCHAR(64),
    comparability_epoch  INT          NOT NULL DEFAULT 1,
    scores               CLOB,        -- JSON: dimension -> score
    strengths            CLOB,
    gaps                 CLOB,
    readiness_band       VARCHAR(20), -- no-hire | lean-hire | hire | strong-hire
    narrative_md         CLOB,

    CONSTRAINT fk_eval_round FOREIGN KEY (round_id) REFERENCES session_round(id)
);

CREATE TABLE session_report (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id     BIGINT       NOT NULL,
    overall_band   VARCHAR(20),
    per_module     CLOB,         -- JSON: per-module scores
    narrative_md   CLOB,

    CONSTRAINT fk_report_session FOREIGN KEY (session_id) REFERENCES interview_session(id)
);

CREATE TABLE readiness_snapshot (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    taken_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    module_type        VARCHAR(30)  NOT NULL,
    company_profile_id VARCHAR(64)  NOT NULL,
    score              DOUBLE       NOT NULL,
    sample_size        INT          NOT NULL DEFAULT 0
);

CREATE TABLE llm_call (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    round_id            BIGINT,
    turn_id             BIGINT,
    provider            VARCHAR(30)  NOT NULL,
    model               VARCHAR(64)  NOT NULL,
    role                VARCHAR(20)  NOT NULL,  -- interviewer | evaluator | summariser
    input_tokens        INT,
    output_tokens       INT,
    cache_read_tokens   INT,
    cache_write_tokens  INT,
    cost_estimate_usd   DOUBLE,
    latency_ms          INT,
    created_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_llm_round FOREIGN KEY (round_id) REFERENCES session_round(id),
    CONSTRAINT fk_llm_turn  FOREIGN KEY (turn_id)  REFERENCES transcript_turn(id)
);

-- Indexes for common access patterns
CREATE INDEX idx_round_session   ON session_round(session_id);
CREATE INDEX idx_turn_round      ON transcript_turn(round_id);
CREATE INDEX idx_artifact_round  ON artifact_snapshot(round_id);
CREATE INDEX idx_signal_round    ON signal(round_id);
CREATE INDEX idx_eval_round      ON round_evaluation(round_id);
CREATE INDEX idx_report_session  ON session_report(session_id);
CREATE INDEX idx_readiness_module ON readiness_snapshot(module_type, company_profile_id);
CREATE INDEX idx_llm_round       ON llm_call(round_id);
