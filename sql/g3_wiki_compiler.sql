-- Apply once after g3_wiki_foundation.sql, only to an explicitly approved database.
-- No startup migration, production deployment, or G2 tables/ledger changes.
ALTER TABLE wiki_compile_job DROP CHECK ck_wiki_job_state;
ALTER TABLE wiki_compile_job ADD CONSTRAINT ck_wiki_job_state
    CHECK (state IN ('QUEUED','RUNNING','DRAFT_READY','FAILED','CANCELLED','INVALIDATED'));

CREATE TABLE wiki_compile_budget (
    request_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
    model VARCHAR(100) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    max_calls INT NOT NULL,
    max_units BIGINT NOT NULL,
    output_tokens INT NOT NULL,
    deadline_at DATETIME(6) NOT NULL,
    first_stop VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin,
    CONSTRAINT fk_wiki_budget_job FOREIGN KEY (request_id) REFERENCES wiki_compile_job(request_id),
    CONSTRAINT ck_wiki_budget_limits CHECK (max_calls BETWEEN 1 AND 64 AND max_units BETWEEN 1 AND 2000000
        AND output_tokens BETWEEN 64 AND 4096)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- An attempt is committed before handing data to the model. NULL usage always means UNKNOWN.
CREATE TABLE wiki_compile_attempt (
    request_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    call_no INT NOT NULL,
    phase VARCHAR(12) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    input_bytes INT NOT NULL,
    reserved_units BIGINT NOT NULL,
    state VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    usage_tokens BIGINT,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (request_id, call_no),
    CONSTRAINT fk_wiki_attempt_budget FOREIGN KEY (request_id) REFERENCES wiki_compile_budget(request_id),
    CONSTRAINT ck_wiki_attempt_state CHECK (state IN ('PREPARED','DISPATCHING','SUCCEEDED','UNKNOWN','NOT_SENT')),
    CONSTRAINT ck_wiki_attempt_units CHECK (input_bytes > 0 AND reserved_units > 0 AND (usage_tokens IS NULL OR usage_tokens >= 0))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE wiki_compile_topic (
    request_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    topic_key VARCHAR(100) NOT NULL,
    snapshot_id BIGINT NOT NULL,
    PRIMARY KEY (request_id, topic_key, snapshot_id),
    KEY ix_wiki_topic_source (snapshot_id, request_id),
    CONSTRAINT fk_wiki_topic_dependency FOREIGN KEY (request_id,snapshot_id)
        REFERENCES wiki_job_source(request_id,snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
