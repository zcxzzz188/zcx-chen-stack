-- Apply once after G2 Catalog, G3 foundation/compiler, and the article schema in an approved database.
-- Required for P3 visibility: a transient withdrawal/edit must revoke old Wiki authorization even without a read.
-- No startup migration. Triggers participate in the originating transaction; an error/deadlock aborts the write.
-- String comparisons are byte-exact even if the existing article table uses a case-insensitive collation.
CREATE TRIGGER wiki_article_changed AFTER UPDATE ON article FOR EACH ROW
UPDATE wiki_source SET active=FALSE,generation=generation+1
WHERE source_type='ARTICLE' AND source_key IN (CAST(OLD.id AS CHAR),CAST(NEW.id AS CHAR)) AND active=TRUE
AND (NOT (OLD.id <=> NEW.id)
    OR NOT (OLD.user_id <=> NEW.user_id)
    OR NOT (BINARY OLD.title <=> BINARY NEW.title)
    OR NOT (BINARY OLD.description <=> BINARY NEW.description)
    OR NOT (BINARY OLD.content <=> BINARY NEW.content)
    OR NOT (BINARY OLD.tag <=> BINARY NEW.tag)
    OR NOT (OLD.reprint_type <=> NEW.reprint_type)
    OR NOT (BINARY OLD.reprint_url <=> BINARY NEW.reprint_url)
    OR NOT (OLD.edit_status <=> NEW.edit_status)
    OR NOT (OLD.examine_status <=> NEW.examine_status)
    OR NOT (OLD.visible_range <=> NEW.visible_range)
    OR NOT (OLD.is_deleted <=> NEW.is_deleted));

CREATE TRIGGER wiki_article_deleted AFTER DELETE ON article FOR EACH ROW
UPDATE wiki_source SET active=FALSE,generation=generation+1
WHERE source_type='ARTICLE' AND source_key=CAST(OLD.id AS CHAR) AND active=TRUE;

CREATE TRIGGER wiki_help_changed AFTER UPDATE ON rag_source_state FOR EACH ROW
UPDATE wiki_source SET active=FALSE,generation=generation+1
WHERE source_type='PLATFORM_HELP' AND source_key IN (OLD.source_id,NEW.source_id) AND active=TRUE
AND (NOT (BINARY OLD.source_id <=> BINARY NEW.source_id)
    OR NOT (BINARY OLD.version <=> BINARY NEW.version)
    OR NOT (BINARY OLD.document_json <=> BINARY NEW.document_json)
    OR NOT (BINARY OLD.content_sha256 <=> BINARY NEW.content_sha256)
    OR NOT (OLD.generation <=> NEW.generation)
    OR NOT (OLD.tombstone <=> NEW.tombstone)
    OR NOT (OLD.deleted <=> NEW.deleted));

CREATE TRIGGER wiki_help_deleted AFTER DELETE ON rag_source_state FOR EACH ROW
UPDATE wiki_source SET active=FALSE,generation=generation+1
WHERE source_type='PLATFORM_HELP' AND source_key=OLD.source_id AND active=TRUE;
