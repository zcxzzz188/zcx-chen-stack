-- Explicit P3 migration, after foundation/compiler/invalidation; no startup DDL.
-- Only apply to an approved database. P2's DRAFT-only constraint stays unchanged.
CREATE TABLE wiki_page (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    current_revision_id BIGINT NULL,
    withdrawn BOOLEAN NOT NULL DEFAULT TRUE,
    published_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY ix_wiki_public (withdrawn,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

CREATE TABLE wiki_revision (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    page_id BIGINT NOT NULL,
    version INT NOT NULL,
    request_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(160) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    markdown MEDIUMTEXT NOT NULL,
    body_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    manifest_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DRAFT',
    approved_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    approved_by INT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_wiki_version(page_id,version),
    UNIQUE KEY uk_wiki_revision_page(page_id,id),
    CONSTRAINT fk_wiki_revision_page FOREIGN KEY(page_id) REFERENCES wiki_page(id),
    CONSTRAINT fk_wiki_revision_draft FOREIGN KEY(request_id) REFERENCES wiki_draft(request_id),
    CONSTRAINT ck_wiki_revision_state CHECK(state IN ('DRAFT','IN_REVIEW','APPROVED','PUBLISHED')),
    CONSTRAINT ck_wiki_revision_approval CHECK(state IN ('DRAFT','IN_REVIEW') OR (approved_hash IS NOT NULL AND approved_by > 0)),
    CONSTRAINT ck_wiki_revision_version CHECK(version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

ALTER TABLE wiki_page ADD CONSTRAINT fk_wiki_current_revision
    FOREIGN KEY(id,current_revision_id) REFERENCES wiki_revision(page_id,id);

CREATE TABLE wiki_review_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    page_id BIGINT NOT NULL,
    revision_id BIGINT NULL,
    action VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    actor_id INT NOT NULL,
    body_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    CONSTRAINT fk_wiki_event_page FOREIGN KEY(page_id) REFERENCES wiki_page(id),
    CONSTRAINT fk_wiki_event_revision FOREIGN KEY(revision_id) REFERENCES wiki_revision(id),
    CONSTRAINT ck_wiki_event_actor CHECK(actor_id > 0),
    CONSTRAINT ck_wiki_review_action CHECK(action IN ('CREATE','SUBMIT','APPROVE','PUBLISH','WITHDRAW','RESTORE'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
