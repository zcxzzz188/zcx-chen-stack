-- MySQL 8.0.16+: explicit, additive Wiki foundation migration; never run on application startup.
-- Select an approved database before applying. No G2/article tables or knowledge are changed.
CREATE TABLE IF NOT EXISTS wiki_source (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_type VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_key VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation BIGINT NOT NULL,
    allowed_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    active BOOLEAN NOT NULL,
    authorized_by INT NOT NULL,
    UNIQUE KEY uk_wiki_origin (source_type, source_key),
    CONSTRAINT ck_wiki_source_type CHECK (source_type IN ('ARTICLE', 'PLATFORM_HELP')),
    CONSTRAINT ck_wiki_generation CHECK (generation >= 0),
    CONSTRAINT ck_wiki_operator CHECK (authorized_by > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS wiki_source_snapshot (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_id BIGINT NOT NULL,
    generation BIGINT NOT NULL,
    fingerprint CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(255) NOT NULL,
    raw_json LONGTEXT NOT NULL,
    parsed_text LONGTEXT NOT NULL,
    origin_version VARCHAR(80) NOT NULL,
    parser_version VARCHAR(40) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_wiki_snapshot_generation (source_id, generation),
    CONSTRAINT fk_wiki_snapshot_source FOREIGN KEY (source_id) REFERENCES wiki_source(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS wiki_compile_job (
    request_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    manifest_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    contract_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT ck_wiki_job_state CHECK (state IN ('QUEUED','DRAFT_READY','CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS wiki_job_source (
    request_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    snapshot_id BIGINT NOT NULL,
    PRIMARY KEY (request_id, snapshot_id),
    KEY ix_wiki_dependency (snapshot_id, request_id),
    CONSTRAINT fk_wiki_dependency_job FOREIGN KEY (request_id) REFERENCES wiki_compile_job(request_id) ON DELETE RESTRICT,
    CONSTRAINT fk_wiki_dependency_snapshot FOREIGN KEY (snapshot_id) REFERENCES wiki_source_snapshot(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS wiki_draft (
    request_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    markdown MEDIUMTEXT NOT NULL,
    body_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DRAFT',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_wiki_draft_job FOREIGN KEY (request_id) REFERENCES wiki_compile_job(request_id) ON DELETE RESTRICT,
    CONSTRAINT ck_wiki_draft_only CHECK (state = 'DRAFT')
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

