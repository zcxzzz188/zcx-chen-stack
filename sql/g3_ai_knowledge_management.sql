-- MySQL 8.0；仅新增命名空间9300-9350，不修改既有菜单、角色、用户或授权。
-- 必须先在隔离库验证；再次执行幂等。回滚脚本只删除本迁移保留ID且拒绝已扩展授权。
DELIMITER $$
CREATE PROCEDURE chen_g3_ai_management_apply()
BEGIN
    DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;
    START TRANSACTION;
    IF EXISTS (SELECT 1 FROM sys_menu WHERE (id=9300 AND (path<>'/ai' OR component<>' ' OR parent_id<>0 OR name<>'AI知识管理' OR sort<>9 OR icon<>'Cpu' OR status<>0 OR is_deleted<>0)) OR (id=9301 AND (path<>'/ai/rag' OR component<>'/ai/rag' OR parent_id<>9300 OR name<>'原始知识' OR sort<>0 OR icon<>'Collection' OR status<>0 OR is_deleted<>0)) OR (id=9302 AND (path<>'/ai/wiki' OR component<>'/ai/wiki' OR parent_id<>9300 OR name<>'整理知识' OR sort<>1 OR icon<>'Document' OR status<>0 OR is_deleted<>0)) OR (id=9303 AND (path<>'/ai/sources' OR component<>'/ai/sources' OR parent_id<>9300 OR name<>'知识来源' OR sort<>2 OR icon<>'Link' OR status<>0 OR is_deleted<>0)) OR (id=9304 AND (path<>'/ai/tasks' OR component<>'/ai/tasks' OR parent_id<>9300 OR name<>'智能整理' OR sort<>3 OR icon<>'MagicStick' OR status<>0 OR is_deleted<>0)) OR (id=9305 AND (path<>'/ai/search' OR component<>'/ai/search' OR parent_id<>9300 OR name<>'检索验证' OR sort<>4 OR icon<>'Search' OR status<>0 OR is_deleted<>0))) OR EXISTS (SELECT 1 FROM sys_permission WHERE (id=9310 AND (permission<>'ai:rag:read' OR description<>'查看原始知识' OR menu_id<>9301 OR is_deleted<>0)) OR (id=9311 AND (permission<>'ai:rag:manage' OR description<>'管理原始知识' OR menu_id<>9301 OR is_deleted<>0)) OR (id=9320 AND (permission<>'ai:wiki:read' OR description<>'查看整理知识' OR menu_id<>9302 OR is_deleted<>0)) OR (id=9321 AND (permission<>'ai:wiki:edit' OR description<>'编辑整理知识' OR menu_id<>9302 OR is_deleted<>0)) OR (id=9322 AND (permission<>'ai:wiki:review' OR description<>'审核整理知识' OR menu_id<>9302 OR is_deleted<>0)) OR (id=9323 AND (permission<>'ai:wiki:publish' OR description<>'发布整理知识' OR menu_id<>9302 OR is_deleted<>0)) OR (id=9330 AND (permission<>'ai:wiki:read' OR description<>'查看知识来源' OR menu_id<>9303 OR is_deleted<>0)) OR (id=9331 AND (permission<>'ai:source:manage' OR description<>'授权及撤销来源' OR menu_id<>9303 OR is_deleted<>0)) OR (id=9340 AND (permission<>'ai:wiki:read' OR description<>'查看整理任务' OR menu_id<>9304 OR is_deleted<>0)) OR (id=9341 AND (permission<>'ai:wiki:compile' OR description<>'发起及取消整理' OR menu_id<>9304 OR is_deleted<>0)) OR (id=9342 AND (permission<>'ai:wiki:edit' OR description<>'保存整理草稿' OR menu_id<>9304 OR is_deleted<>0)) OR (id=9350 AND (permission<>'ai:knowledge:test' OR description<>'检索及来源验证' OR menu_id<>9305 OR is_deleted<>0))) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AI_MANAGEMENT_RESERVED_ID_CONFLICT';
    END IF;
    IF EXISTS (SELECT 1 FROM sys_menu WHERE path IN ('/ai','/ai/rag','/ai/wiki','/ai/sources','/ai/tasks','/ai/search') AND id NOT IN (9300,9301,9302,9303,9304,9305)) THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='AI_MANAGEMENT_ROUTE_CONFLICT';
    END IF;
    INSERT INTO sys_menu(id,parent_id,name,sort,path,component,icon,status,create_time,update_time,is_deleted)
        SELECT 9300,0,'AI知识管理',9,'/ai',' ','Cpu',0,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id=9300);
    INSERT INTO sys_menu(id,parent_id,name,sort,path,component,icon,status,create_time,update_time,is_deleted)
        SELECT 9301,9300,'原始知识',0,'/ai/rag','/ai/rag','Collection',0,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id=9301);
    INSERT INTO sys_menu(id,parent_id,name,sort,path,component,icon,status,create_time,update_time,is_deleted)
        SELECT 9302,9300,'整理知识',1,'/ai/wiki','/ai/wiki','Document',0,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id=9302);
    INSERT INTO sys_menu(id,parent_id,name,sort,path,component,icon,status,create_time,update_time,is_deleted)
        SELECT 9303,9300,'知识来源',2,'/ai/sources','/ai/sources','Link',0,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id=9303);
    INSERT INTO sys_menu(id,parent_id,name,sort,path,component,icon,status,create_time,update_time,is_deleted)
        SELECT 9304,9300,'智能整理',3,'/ai/tasks','/ai/tasks','MagicStick',0,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id=9304);
    INSERT INTO sys_menu(id,parent_id,name,sort,path,component,icon,status,create_time,update_time,is_deleted)
        SELECT 9305,9300,'检索验证',4,'/ai/search','/ai/search','Search',0,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE id=9305);
    INSERT INTO sys_permission(id,description,permission,menu_id,create_time,update_time,is_deleted)
        SELECT 9310,'查看原始知识','ai:rag:read',9301,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id=9310);
    INSERT INTO sys_permission(id,description,permission,menu_id,create_time,update_time,is_deleted)
        SELECT 9311,'管理原始知识','ai:rag:manage',9301,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id=9311);
    INSERT INTO sys_permission(id,description,permission,menu_id,create_time,update_time,is_deleted)
        SELECT 9320,'查看整理知识','ai:wiki:read',9302,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id=9320);
    INSERT INTO sys_permission(id,description,permission,menu_id,create_time,update_time,is_deleted)
        SELECT 9321,'编辑整理知识','ai:wiki:edit',9302,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id=9321);
    INSERT INTO sys_permission(id,description,permission,menu_id,create_time,update_time,is_deleted)
        SELECT 9322,'审核整理知识','ai:wiki:review',9302,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id=9322);
    INSERT INTO sys_permission(id,description,permission,menu_id,create_time,update_time,is_deleted)
        SELECT 9323,'发布整理知识','ai:wiki:publish',9302,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id=9323);
    INSERT INTO sys_permission(id,description,permission,menu_id,create_time,update_time,is_deleted)
        SELECT 9330,'查看知识来源','ai:wiki:read',9303,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id=9330);
    INSERT INTO sys_permission(id,description,permission,menu_id,create_time,update_time,is_deleted)
        SELECT 9331,'授权及撤销来源','ai:source:manage',9303,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id=9331);
    INSERT INTO sys_permission(id,description,permission,menu_id,create_time,update_time,is_deleted)
        SELECT 9340,'查看整理任务','ai:wiki:read',9304,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id=9340);
    INSERT INTO sys_permission(id,description,permission,menu_id,create_time,update_time,is_deleted)
        SELECT 9341,'发起及取消整理','ai:wiki:compile',9304,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id=9341);
    INSERT INTO sys_permission(id,description,permission,menu_id,create_time,update_time,is_deleted)
        SELECT 9342,'保存整理草稿','ai:wiki:edit',9304,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id=9342);
    INSERT INTO sys_permission(id,description,permission,menu_id,create_time,update_time,is_deleted)
        SELECT 9350,'检索及来源验证','ai:knowledge:test',9305,NOW(),NOW(),0
        WHERE NOT EXISTS (SELECT 1 FROM sys_permission WHERE id=9350);
    INSERT INTO sys_role_menu(role_id,menu_id)
        SELECT r.id,m.id FROM sys_role r JOIN sys_menu m ON m.id IN (9300,9301,9302,9303,9304,9305)
        WHERE r.role='admin' AND r.status=0 AND r.is_deleted=0
        AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=r.id AND rm.menu_id=m.id);
    -- 固定 content_admin 仅预置操作能力；仍须另行分配 AI 根菜单及子菜单才生效。
    INSERT INTO sys_role_permission(role_id,permission_id)
        SELECT r.id,p.id FROM sys_role r JOIN sys_permission p ON p.id IN (9310,9311,9320,9321,9322,9323,9330,9331,9340,9341,9342,9350)
        WHERE r.role IN ('admin','content_admin') AND r.status=0 AND r.is_deleted=0
        AND NOT EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.role_id=r.id AND rp.permission_id=p.id);
    COMMIT;
END$$
DELIMITER ;
CALL chen_g3_ai_management_apply();
DROP PROCEDURE chen_g3_ai_management_apply;
