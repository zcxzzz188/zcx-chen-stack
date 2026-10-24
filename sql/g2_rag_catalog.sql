-- MySQL 8.0: only create dedicated RAG tables. This file does not initialize or publish knowledge.
-- Review the selected database before explicitly applying; existing tables are never altered.
CREATE TABLE IF NOT EXISTS rag_catalog_state (
    id TINYINT UNSIGNED NOT NULL,
    generation BIGINT UNSIGNED NOT NULL,
    snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    schema_version INT UNSIGNED NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_rag_catalog_singleton CHECK (id = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS rag_source_state (
    source_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    document_json JSON NOT NULL,
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation BIGINT UNSIGNED NOT NULL,
    tombstone BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version_history JSON NOT NULL,
    PRIMARY KEY (source_id),
    CONSTRAINT chk_rag_source_deleted_tombstone CHECK (deleted = FALSE OR tombstone = TRUE)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- Release operations and events are audit/idempotency records, not another knowledge Catalog.
-- Legacy names remain schema-compatible: approval_sha256 is the raw local operation-record hash;
-- approver_id stores CONTROLLED_OPERATOR, not a signature, signer or cryptographic approval.
CREATE TABLE IF NOT EXISTS rag_release_operation (
    operation_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    manifest_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    approval_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    environment VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    operator_id VARCHAR(128) NOT NULL,
    approver_id VARCHAR(128) NOT NULL,
    operation_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expected_generation BIGINT UNSIGNED NOT NULL,
    applied_generation BIGINT UNSIGNED NULL,
    applied_snapshot_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    target_collection VARCHAR(95) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    max_embedding_requests BIGINT UNSIGNED NOT NULL,
    embedding_requests BIGINT UNSIGNED NOT NULL DEFAULT 0,
    target_initialized BOOLEAN NOT NULL DEFAULT FALSE,
    index_authority VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    index_epoch BIGINT UNSIGNED NULL,
    last_error_code VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (operation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE IF NOT EXISTS rag_release_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    operation_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_type VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    generation BIGINT UNSIGNED NULL,
    details_code VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_rag_release_event_operation (operation_id, id),
    CONSTRAINT fk_rag_release_event_operation FOREIGN KEY (operation_id)
        REFERENCES rag_release_operation (operation_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
