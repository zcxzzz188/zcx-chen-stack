-- MySQL 8.0.16+；G3 AI知识配置与人工新增知识迁移。
-- 只新增独立表与命名空间9306/9360-9361菜单权限，不修改既有表、角色、用户或已授权菜单。
-- 必须先在隔离库验证；重复执行幂等；回滚脚本见 g3_ai_knowledge_configuration_rollback.sql。

-- 运行配置（知识模式/每日额度）持久化；不保存提示词、凭据或用户内容。
CREATE TABLE IF NOT EXISTS ai_runtime_config (
    config_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    config_value VARCHAR(128) NOT NULL,
    config_version BIGINT UNSIGNED NOT NULL DEFAULT 1,
    updated_by INT NOT NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (config_key),
    CONSTRAINT ck_ai_runtime_config_operator CHECK (updated_by > 0),
    CONSTRAINT ck_ai_runtime_config_version CHECK (config_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 修改审计只追加；不提供业务删除入口。
CREATE TABLE IF NOT EXISTS ai_runtime_config_event (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    old_value VARCHAR(128) NULL,
    new_value VARCHAR(128) NOT NULL,
    actor_id INT NOT NULL,
    config_version BIGINT UNSIGNED NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    KEY ix_ai_runtime_config_event_key (config_key, id),
    CONSTRAINT ck_ai_runtime_config_event_actor CHECK (actor_id > 0),
    CONSTRAINT ck_ai_runtime_config_event_version CHECK (config_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 管理员人工新增RAG知识的草稿；发布后只保留草稿状态和代次，正文进入正式知识目录。
CREATE TABLE IF NOT EXISTS rag_knowledge_draft (
    id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(160) NOT NULL,
    text MEDIUMTEXT NOT NULL,
    version VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    source_url VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    keywords_json JSON NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'DRAFT',
    created_by INT NOT NULL,
    updated_by INT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    publish_request_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NULL,
    published_generation BIGINT UNSIGNED NULL,
    published_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rag_knowledge_draft_source (source_id, state),
    CONSTRAINT ck_rag_knowledge_draft_state CHECK (state IN ('DRAFT','PUBLISHED')),
    CONSTRAINT ck_rag_knowledge_draft_actor CHECK (created_by > 0 AND updated_by > 0),
    CONSTRAINT ck_rag_knowledge_draft_text_size CHECK (CHAR_LENGTH(text) BETWEEN 1 AND 20000),
    CONSTRAINT ck_rag_knowledge_draft_published CHECK (
        (state='DRAFT' AND published_at IS NULL) OR (state='PUBLISHED' AND published_at IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

-- 被替换版本正文归档；原RAG目录的代次与version_history仍保留为权威摘要。
CREATE TABLE IF NOT EXISTS rag_knowledge_version (
    id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    source_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    version VARCHAR(40) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    title VARCHAR(160) NOT NULL,
    text MEDIUMTEXT NOT NULL,
    source_url VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    keywords_json JSON NOT NULL,
    content_sha256 CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    state VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'PENDING',
    generation BIGINT UNSIGNED NULL,
    created_by INT NOT NULL,
    archived_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    UNIQUE KEY uk_rag_knowledge_version (source_id, version),
    KEY ix_rag_knowledge_version_source (source_id, archived_at),
    CONSTRAINT ck_rag_knowledge_version_state CHECK (state IN ('PENDING','SUPERSEDED','REVOKED')),
    CONSTRAINT ck_rag_knowledge_version_actor CHECK (created_by > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;

DELIMITER $$
CREATE PROCEDURE chen_g3_ai_configuration_apply()
BEGIN
    DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
    START TRANSACTION;
    IF EXISTS (SELECT 1 FROM sys_menu WHERE (id=9306 AND (path<>'/ai/config' OR component<>'/ai/config' OR parent_id<>9300 OR name<>'AI配置' OR sort<>5 OR icon<>'Setting' OR status<>0 OR is_deleted<>0))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AI_CONFIGURATION_RESERVED_ID_CONFLICT';
    END IF;
    IF EXISTS (SELECT 1 FROM sys_menu WHERE path IN ('/ai/config') AND id NOT IN (9306)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AI_CONFIGURATION_ROUTE_CONFLICT';
    END IF;
    IF EXISTS (SELECT 1 FROM sys_permission WHERE (id=9360 AND (permission<>'ai:config:read' OR description<>'查看AI配置' OR menu_id<>9306 OR is_deleted<>0)) OR (id=9361 AND (permission<>'ai:config:manage' OR description<>'修改AI配置' OR menu_id<>9306 OR is_deleted<>0))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AI_CONFIGURATION_PERMISSION_CONFLICT';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM sys_menu WHERE id=9300 AND path='/ai' AND is_deleted=0) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AI_CONFIGURATION_PARENT_MENU_REQUIRED';
    END IF;
    INSERT INTO sys_menu(id,parent_id,name,sort,path,component,icon,status,create_time,update_time,is_deleted)
        SELECT 9306,9300,'AI配置',5,'/ai/config','/ai/config','Setting',0,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id=9306);
    INSERT INTO sys_permission(id,description,permission,menu_id,create_time,update_time,is_deleted)
        SELECT 9360,'查看AI配置','ai:config:read',9306,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id=9360);
    INSERT INTO sys_permission(id,description,permission,menu_id,create_time,update_time,is_deleted)
        SELECT 9361,'修改AI配置','ai:config:manage',9306,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id=9361);
    INSERT INTO sys_role_menu(role_id,menu_id)
        SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.id=9306
        WHERE r.role='admin' AND r.status=0 AND r.is_deleted=0
        AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=m.id);
    -- content_admin 仅预置操作能力；仍须另行分配AI根菜单及AI配置子菜单才生效。
    INSERT INTO sys_role_permission(role_id,permission_id)
        SELECT r.id,p.id FROM sys_role r JOIN sys_permission p ON p.id IN (9360,9361)
        WHERE r.role IN ('admin','content_admin') AND r.status=0 AND r.is_deleted=0
        AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
    COMMIT;
END$$
DELIMITER ;
CALL chen_g3_ai_configuration_apply();
DROP PROCEDURE chen_g3_ai_configuration_apply;
