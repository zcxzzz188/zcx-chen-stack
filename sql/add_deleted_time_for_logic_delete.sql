-- 为全部 MyBatis-Plus 逻辑删除表补充 deleted_time 字段、清理索引和逻辑删除时间触发器。
-- 现有 is_deleted = 1 且 deleted_time IS NULL 的历史记录不会进入自动清理范围，避免迁移后误删。后续必须单独确认后再决定是否补时间。

ALTER TABLE `article`
    ADD COLUMN `deleted_time` datetime NULL DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间' AFTER `is_deleted`,
    ADD INDEX `idx_is_deleted_deleted_time` (`is_deleted`, `deleted_time`);

ALTER TABLE `article_column`
    ADD COLUMN `deleted_time` datetime NULL DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间' AFTER `is_deleted`,
    ADD INDEX `idx_is_deleted_deleted_time` (`is_deleted`, `deleted_time`);

ALTER TABLE `column`
    ADD COLUMN `deleted_time` datetime NULL DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间' AFTER `is_deleted`,
    ADD INDEX `idx_is_deleted_deleted_time` (`is_deleted`, `deleted_time`);

ALTER TABLE `comment`
    ADD COLUMN `deleted_time` datetime NULL DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间' AFTER `is_deleted`,
    ADD INDEX `idx_is_deleted_deleted_time` (`is_deleted`, `deleted_time`);

ALTER TABLE `conversation`
    ADD COLUMN `deleted_time` datetime NULL DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间' AFTER `is_deleted`,
    ADD INDEX `idx_is_deleted_deleted_time` (`is_deleted`, `deleted_time`);

ALTER TABLE `favorite`
    ADD COLUMN `deleted_time` datetime NULL DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间' AFTER `is_deleted`,
    ADD INDEX `idx_is_deleted_deleted_time` (`is_deleted`, `deleted_time`);

ALTER TABLE `history`
    ADD COLUMN `deleted_time` datetime NULL DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间' AFTER `is_deleted`,
    ADD INDEX `idx_is_deleted_deleted_time` (`is_deleted`, `deleted_time`);

ALTER TABLE `message`
    ADD COLUMN `deleted_time` datetime NULL DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间' AFTER `is_deleted`,
    ADD INDEX `idx_is_deleted_deleted_time` (`is_deleted`, `deleted_time`);

ALTER TABLE `photo`
    ADD COLUMN `deleted_time` datetime NULL DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间' AFTER `is_deleted`,
    ADD INDEX `idx_is_deleted_deleted_time` (`is_deleted`, `deleted_time`);

ALTER TABLE `private_message`
    ADD COLUMN `deleted_time` datetime NULL DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间' AFTER `is_deleted`,
    ADD INDEX `idx_is_deleted_deleted_time` (`is_deleted`, `deleted_time`);

ALTER TABLE `sys_menu`
    ADD COLUMN `deleted_time` datetime NULL DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间' AFTER `is_deleted`,
    ADD INDEX `idx_is_deleted_deleted_time` (`is_deleted`, `deleted_time`);

ALTER TABLE `sys_operationlog`
    ADD COLUMN `deleted_time` datetime NULL DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间' AFTER `is_deleted`,
    ADD INDEX `idx_is_deleted_deleted_time` (`is_deleted`, `deleted_time`);

ALTER TABLE `sys_permission`
    ADD COLUMN `deleted_time` datetime NULL DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间' AFTER `is_deleted`,
    ADD INDEX `idx_is_deleted_deleted_time` (`is_deleted`, `deleted_time`);

ALTER TABLE `sys_role`
    ADD COLUMN `deleted_time` datetime NULL DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间' AFTER `is_deleted`,
    ADD INDEX `idx_is_deleted_deleted_time` (`is_deleted`, `deleted_time`);

ALTER TABLE `sys_user`
    ADD COLUMN `deleted_time` datetime NULL DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间' AFTER `is_deleted`,
    ADD INDEX `idx_is_deleted_deleted_time` (`is_deleted`, `deleted_time`);

DROP TRIGGER IF EXISTS `trg_article_logic_delete_time`;
DROP TRIGGER IF EXISTS `trg_article_column_logic_delete_time`;
DROP TRIGGER IF EXISTS `trg_column_logic_delete_time`;
DROP TRIGGER IF EXISTS `trg_comment_logic_delete_time`;
DROP TRIGGER IF EXISTS `trg_conversation_logic_delete_time`;
DROP TRIGGER IF EXISTS `trg_favorite_logic_delete_time`;
DROP TRIGGER IF EXISTS `trg_history_logic_delete_time`;
DROP TRIGGER IF EXISTS `trg_message_logic_delete_time`;
DROP TRIGGER IF EXISTS `trg_photo_logic_delete_time`;
DROP TRIGGER IF EXISTS `trg_private_message_logic_delete_time`;
DROP TRIGGER IF EXISTS `trg_sys_menu_logic_delete_time`;
DROP TRIGGER IF EXISTS `trg_sys_operationlog_logic_delete_time`;
DROP TRIGGER IF EXISTS `trg_sys_permission_logic_delete_time`;
DROP TRIGGER IF EXISTS `trg_sys_role_logic_delete_time`;
DROP TRIGGER IF EXISTS `trg_sys_user_logic_delete_time`;

DELIMITER //

CREATE TRIGGER `trg_article_logic_delete_time`
BEFORE UPDATE ON `article`
FOR EACH ROW
BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END//

CREATE TRIGGER `trg_article_column_logic_delete_time`
BEFORE UPDATE ON `article_column`
FOR EACH ROW
BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END//

CREATE TRIGGER `trg_column_logic_delete_time`
BEFORE UPDATE ON `column`
FOR EACH ROW
BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END//

CREATE TRIGGER `trg_comment_logic_delete_time`
BEFORE UPDATE ON `comment`
FOR EACH ROW
BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END//

CREATE TRIGGER `trg_conversation_logic_delete_time`
BEFORE UPDATE ON `conversation`
FOR EACH ROW
BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END//

CREATE TRIGGER `trg_favorite_logic_delete_time`
BEFORE UPDATE ON `favorite`
FOR EACH ROW
BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END//

CREATE TRIGGER `trg_history_logic_delete_time`
BEFORE UPDATE ON `history`
FOR EACH ROW
BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END//

CREATE TRIGGER `trg_message_logic_delete_time`
BEFORE UPDATE ON `message`
FOR EACH ROW
BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END//

CREATE TRIGGER `trg_photo_logic_delete_time`
BEFORE UPDATE ON `photo`
FOR EACH ROW
BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END//

CREATE TRIGGER `trg_private_message_logic_delete_time`
BEFORE UPDATE ON `private_message`
FOR EACH ROW
BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END//

CREATE TRIGGER `trg_sys_menu_logic_delete_time`
BEFORE UPDATE ON `sys_menu`
FOR EACH ROW
BEGIN
    IF IFNULL(OLD.`is_deleted`, 0) = 0 AND IFNULL(NEW.`is_deleted`, 0) = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF IFNULL(OLD.`is_deleted`, 0) = 1 AND IFNULL(NEW.`is_deleted`, 0) = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END//

CREATE TRIGGER `trg_sys_operationlog_logic_delete_time`
BEFORE UPDATE ON `sys_operationlog`
FOR EACH ROW
BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END//

CREATE TRIGGER `trg_sys_permission_logic_delete_time`
BEFORE UPDATE ON `sys_permission`
FOR EACH ROW
BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END//

CREATE TRIGGER `trg_sys_role_logic_delete_time`
BEFORE UPDATE ON `sys_role`
FOR EACH ROW
BEGIN
    IF IFNULL(OLD.`is_deleted`, 0) = 0 AND IFNULL(NEW.`is_deleted`, 0) = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF IFNULL(OLD.`is_deleted`, 0) = 1 AND IFNULL(NEW.`is_deleted`, 0) = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END//

CREATE TRIGGER `trg_sys_user_logic_delete_time`
BEFORE UPDATE ON `sys_user`
FOR EACH ROW
BEGIN
    IF IFNULL(OLD.`is_deleted`, 0) = 0 AND IFNULL(NEW.`is_deleted`, 0) = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF IFNULL(OLD.`is_deleted`, 0) = 1 AND IFNULL(NEW.`is_deleted`, 0) = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END//

DELIMITER ;
