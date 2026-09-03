-- V4: candidate resume, for the resume-deep-dive module.
-- Single-user app: at most one active resume at a time, replaced on re-upload.
-- Only the extracted TEXT is persisted — the uploaded file's bytes are never written to
-- disk, parsed in-memory and discarded immediately (resume content is real PII).

CREATE TABLE candidate_resume (
    id                 BIGINT AUTO_INCREMENT PRIMARY KEY,
    original_filename  VARCHAR(255),
    content_text       CLOB         NOT NULL,
    content_hash       VARCHAR(64)  NOT NULL,
    uploaded_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
