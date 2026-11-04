-- MySQL 8.0；撤回G3 AI知识配置迁移。已发生自定义授权或额外子菜单时停止。
-- 注意：回滚会删除已保存的运行配置和人工知识草稿/归档版本；只应在明确回退时执行。
DELIMITER $$
CREATE PROCEDURE chen_g3_ai_configuration_rollback()
BEGIN
    DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
    START TRANSACTION;
    IF EXISTS (SELECT 1 FROM sys_menu WHERE (id=9306 AND (path<>'/ai/config' OR component<>'/ai/config' OR parent_id<>9300 OR name<>'AI配置' OR sort<>5 OR icon<>'Setting' OR status<>0 OR is_deleted<>0))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AI_CONFIGURATION_CUSTOMIZED_STOP';
    END IF;
    IF EXISTS (SELECT 1 FROM sys_permission WHERE (id=9360 AND (permission<>'ai:config:read' OR description<>'查看AI配置' OR menu_id<>9306 OR is_deleted<>0)) OR (id=9361 AND (permission<>'ai:config:manage' OR description<>'修改AI配置' OR menu_id<>9306 OR is_deleted<>0))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AI_CONFIGURATION_PERMISSION_CUSTOMIZED_STOP';
    END IF;
    IF EXISTS (SELECT 1 FROM sys_role_menu rm LEFT JOIN sys_role r ON r.id=rm.role_id WHERE rm.menu_id=9306 AND (r.role IS NULL OR r.role<>'admin'))
       OR EXISTS (SELECT 1 FROM sys_role_permission rp LEFT JOIN sys_role r ON r.id=rp.role_id WHERE rp.permission_id IN (9360,9361) AND (r.role IS NULL OR r.role NOT IN ('admin','content_admin')))
       OR EXISTS (SELECT 1 FROM sys_menu WHERE parent_id=9300 AND id NOT IN (9300,9301,9302,9303,9304,9305,9306))
       OR EXISTS (SELECT 1 FROM sys_permission WHERE menu_id=9306 AND id NOT IN (9360,9361)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AI_CONFIGURATION_NEW_ASSIGNMENTS_STOP';
    END IF;
    DELETE FROM sys_role_permission WHERE permission_id IN (9360,9361);
    DELETE FROM sys_role_menu WHERE menu_id=9306;
    DELETE FROM sys_permission WHERE id IN (9360,9361);
    DELETE FROM sys_menu WHERE id=9306;
    COMMIT;
END$$
DELIMITER ;
CALL chen_g3_ai_configuration_rollback();
DROP PROCEDURE chen_g3_ai_configuration_rollback;
DROP TABLE IF EXISTS rag_knowledge_version;
DROP TABLE IF EXISTS rag_knowledge_draft;
DROP TABLE IF EXISTS ai_runtime_config_event;
DROP TABLE IF EXISTS ai_runtime_config;
