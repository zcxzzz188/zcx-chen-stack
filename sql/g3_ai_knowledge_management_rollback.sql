-- MySQL 8.0；撤回本迁移。已发生自定义授权或结构变更时停止，避免删除管理员后续配置。
DELIMITER $$
CREATE PROCEDURE chen_g3_ai_management_rollback()
BEGIN
    DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
    START TRANSACTION;
    IF EXISTS (SELECT 1 FROM sys_menu WHERE (id=9300 AND (path<>'/ai' OR component<>' ' OR parent_id<>0 OR name<>'AI知识管理' OR sort<>9 OR icon<>'Cpu' OR status<>0 OR is_deleted<>0)) OR (id=9301 AND (path<>'/ai/rag' OR component<>'/ai/rag' OR parent_id<>9300 OR name<>'原始知识' OR sort<>0 OR icon<>'Collection' OR status<>0 OR is_deleted<>0)) OR (id=9302 AND (path<>'/ai/wiki' OR component<>'/ai/wiki' OR parent_id<>9300 OR name<>'整理知识' OR sort<>1 OR icon<>'Document' OR status<>0 OR is_deleted<>0)) OR (id=9303 AND (path<>'/ai/sources' OR component<>'/ai/sources' OR parent_id<>9300 OR name<>'知识来源' OR sort<>2 OR icon<>'Link' OR status<>0 OR is_deleted<>0)) OR (id=9304 AND (path<>'/ai/tasks' OR component<>'/ai/tasks' OR parent_id<>9300 OR name<>'智能整理' OR sort<>3 OR icon<>'MagicStick' OR status<>0 OR is_deleted<>0)) OR (id=9305 AND (path<>'/ai/search' OR component<>'/ai/search' OR parent_id<>9300 OR name<>'检索验证' OR sort<>4 OR icon<>'Search' OR status<>0 OR is_deleted<>0))) OR EXISTS (SELECT 1 FROM sys_permission WHERE (id=9310 AND (permission<>'ai:rag:read' OR description<>'查看原始知识' OR menu_id<>9301 OR is_deleted<>0)) OR (id=9311 AND (permission<>'ai:rag:manage' OR description<>'管理原始知识' OR menu_id<>9301 OR is_deleted<>0)) OR (id=9320 AND (permission<>'ai:wiki:read' OR description<>'查看整理知识' OR menu_id<>9302 OR is_deleted<>0)) OR (id=9321 AND (permission<>'ai:wiki:edit' OR description<>'编辑整理知识' OR menu_id<>9302 OR is_deleted<>0)) OR (id=9322 AND (permission<>'ai:wiki:review' OR description<>'审核整理知识' OR menu_id<>9302 OR is_deleted<>0)) OR (id=9323 AND (permission<>'ai:wiki:publish' OR description<>'发布整理知识' OR menu_id<>9302 OR is_deleted<>0)) OR (id=9330 AND (permission<>'ai:wiki:read' OR description<>'查看知识来源' OR menu_id<>9303 OR is_deleted<>0)) OR (id=9331 AND (permission<>'ai:source:manage' OR description<>'授权及撤销来源' OR menu_id<>9303 OR is_deleted<>0)) OR (id=9340 AND (permission<>'ai:wiki:read' OR description<>'查看整理任务' OR menu_id<>9304 OR is_deleted<>0)) OR (id=9341 AND (permission<>'ai:wiki:compile' OR description<>'发起及取消整理' OR menu_id<>9304 OR is_deleted<>0)) OR (id=9342 AND (permission<>'ai:wiki:edit' OR description<>'保存整理草稿' OR menu_id<>9304 OR is_deleted<>0)) OR (id=9350 AND (permission<>'ai:knowledge:test' OR description<>'检索及来源验证' OR menu_id<>9305 OR is_deleted<>0))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AI_MANAGEMENT_CUSTOMIZED_STOP';
    END IF;
    IF EXISTS (SELECT 1 FROM sys_role_menu rm LEFT JOIN sys_role r ON r.id=rm.role_id WHERE rm.menu_id IN (9300,9301,9302,9303,9304,9305) AND (r.role IS NULL OR r.role<>'admin'))
       OR EXISTS (SELECT 1 FROM sys_role_permission rp LEFT JOIN sys_role r ON r.id=rp.role_id WHERE rp.permission_id IN (9310,9311,9320,9321,9322,9323,9330,9331,9340,9341,9342,9350) AND (r.role IS NULL OR r.role NOT IN ('admin','content_admin')))
       OR EXISTS (SELECT 1 FROM sys_menu WHERE parent_id=9300 AND id NOT IN (9300,9301,9302,9303,9304,9305))
       OR EXISTS (SELECT 1 FROM sys_permission WHERE menu_id IN (9300,9301,9302,9303,9304,9305) AND id NOT IN (9310,9311,9320,9321,9322,9323,9330,9331,9340,9341,9342,9350)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AI_MANAGEMENT_NEW_ASSIGNMENTS_STOP';
    END IF;
    DELETE FROM sys_role_permission WHERE permission_id IN (9310,9311,9320,9321,9322,9323,9330,9331,9340,9341,9342,9350);
    DELETE FROM sys_role_menu WHERE menu_id IN (9300,9301,9302,9303,9304,9305);
    DELETE FROM sys_permission WHERE id IN (9310,9311,9320,9321,9322,9323,9330,9331,9340,9341,9342,9350);
    DELETE FROM sys_menu WHERE id IN (9300,9301,9302,9303,9304,9305);
    COMMIT;
END$$
DELIMITER ;
CALL chen_g3_ai_management_rollback();
DROP PROCEDURE chen_g3_ai_management_rollback;
