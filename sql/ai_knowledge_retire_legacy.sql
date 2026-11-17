-- Apply with application writers stopped, after restoring the pre-retirement backup
-- in an isolated MySQL instance and deploying the canonical G5 application.
-- Run without mysql --force. Recovery requires restoring the archived legacy schema
-- and rows before any rollout of an application that uses them.
SET SESSION lock_wait_timeout = 5;

CREATE TEMPORARY TABLE ai_retirement_guard (ok BOOLEAN NOT NULL CHECK (ok = TRUE));
SELECT COUNT(*) INTO @legacy_table_count FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
  AND table_name IN ('ai_runtime_config','knowledge_audit_log','knowledge_chunk','knowledge_document_g4',
                     'knowledge_document_version','knowledge_import_task','knowledge_index_task',
                     'rag_catalog_state','rag_release_event','rag_release_operation','rag_source_state',
                     'wiki_compile_attempt','wiki_compile_job','wiki_compile_source','wiki_page_g4',
                     'wiki_revision','wiki_source','wiki_source_snapshot');
INSERT INTO ai_retirement_guard VALUES (@legacy_table_count IN (0,18));
INSERT INTO ai_retirement_guard
SELECT COUNT(*) = 0 FROM information_schema.key_column_usage
WHERE referenced_table_schema = DATABASE()
  AND referenced_table_name IN ('ai_runtime_config','knowledge_audit_log','knowledge_chunk','knowledge_document_g4',
                     'knowledge_document_version','knowledge_import_task','knowledge_index_task',
                     'rag_catalog_state','rag_release_event','rag_release_operation','rag_source_state',
                     'wiki_compile_attempt','wiki_compile_job','wiki_compile_source','wiki_page_g4',
                     'wiki_revision','wiki_source','wiki_source_snapshot')
  AND (table_schema <> DATABASE() OR table_name NOT IN
                    ('knowledge_audit_log','knowledge_chunk','knowledge_document_g4','knowledge_document_version',
                     'knowledge_import_task','knowledge_index_task','rag_release_event','wiki_compile_attempt',
                     'wiki_compile_source','wiki_page_g4','wiki_revision','wiki_source_snapshot'));
INSERT INTO ai_retirement_guard
SELECT COUNT(*) = 8 FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'
  AND table_name IN ('knowledge_document','rag_knowledge','rag_chunk','wiki_page',
                     'wiki_page_source','wiki_page_revision','ai_chat_session','ai_chat_message');

SET @retirement_sql = IF(@legacy_table_count = 18, '
INSERT INTO ai_retirement_guard VALUES (
    NOT EXISTS(SELECT 1 FROM knowledge_document_g4 d LEFT JOIN rag_knowledge k ON k.id=d.id
               WHERE NOT d.deleted AND k.id IS NULL)
    AND NOT EXISTS(SELECT 1 FROM knowledge_import_task i LEFT JOIN knowledge_document d ON d.id=i.document_id
                   WHERE i.document_id IS NULL OR d.id IS NULL OR d.file_path IS NULL
                      OR HEX(d.file_sha256)<>UPPER(i.file_sha256) OR d.file_size<>i.file_bytes)
    AND NOT EXISTS(SELECT 1 FROM wiki_page_g4 p LEFT JOIN wiki_page n ON n.id=p.id WHERE n.id IS NULL)
    AND NOT EXISTS(SELECT 1 FROM wiki_revision r LEFT JOIN wiki_page_revision n ON n.wiki_page_id=r.page_id AND n.version=r.version
                   WHERE n.wiki_page_id IS NULL OR NOT(BINARY n.title <=> BINARY r.title)
                      OR NOT(BINARY n.summary <=> BINARY r.summary) OR NOT(BINARY n.content <=> BINARY r.markdown))
    AND NOT EXISTS(SELECT 1 FROM rag_knowledge WHERE enabled AND index_status IN (''UNINDEXED'',''PENDING'',''DELETING''))
    AND NOT EXISTS(SELECT 1 FROM wiki_source WHERE source_type=''ARTICLE'' AND active)
    AND NOT EXISTS(SELECT 1 FROM knowledge_index_task WHERE status IN (''PREPARED'',''INDEXING'',''UNKNOWN''))
    AND NOT EXISTS(SELECT 1 FROM wiki_compile_job WHERE state NOT IN (''DRAFT_READY'',''FAILED''))
)', 'SELECT ''Legacy AI tables already absent'' AS migration_status');
PREPARE retirement_stmt FROM @retirement_sql;
EXECUTE retirement_stmt;
DEALLOCATE PREPARE retirement_stmt;

DROP TRIGGER IF EXISTS wiki_article_changed;
DROP TRIGGER IF EXISTS wiki_article_deleted;
DROP TRIGGER IF EXISTS wiki_help_changed;
DROP TRIGGER IF EXISTS wiki_help_deleted;
DROP TRIGGER IF EXISTS wiki_knowledge_doc_changed;
DROP TRIGGER IF EXISTS wiki_knowledge_doc_deleted;

SET @retirement_sql = IF(EXISTS(
    SELECT 1 FROM information_schema.referential_constraints
    WHERE constraint_schema=DATABASE() AND table_name='wiki_page_g4' AND constraint_name='fk_wiki_current_revision'
), 'ALTER TABLE wiki_page_g4 DROP FOREIGN KEY fk_wiki_current_revision', 'DO 0');
PREPARE retirement_stmt FROM @retirement_sql;
EXECUTE retirement_stmt;
DEALLOCATE PREPARE retirement_stmt;

SET @retirement_sql = IF(EXISTS(
    SELECT 1 FROM information_schema.referential_constraints
    WHERE constraint_schema=DATABASE() AND table_name='knowledge_document_g4'
      AND constraint_name='fk_knowledge_document_current_version'
), 'ALTER TABLE knowledge_document_g4 DROP FOREIGN KEY fk_knowledge_document_current_version', 'DO 0');
PREPARE retirement_stmt FROM @retirement_sql;
EXECUTE retirement_stmt;
DEALLOCATE PREPARE retirement_stmt;

DROP TABLE IF EXISTS knowledge_audit_log, knowledge_chunk, knowledge_import_task, knowledge_index_task,
    knowledge_document_version, knowledge_document_g4, wiki_compile_attempt, wiki_compile_source, wiki_revision,
    wiki_page_g4, wiki_compile_job, wiki_source_snapshot, wiki_source, rag_release_event, rag_release_operation,
    rag_catalog_state, rag_source_state, ai_runtime_config;
DROP TEMPORARY TABLE ai_retirement_guard;
