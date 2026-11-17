-- Chen Stack current schema for a new, empty application database.
-- Select the target database before running this file. MySQL 8.0 is required.
CREATE TEMPORARY TABLE bootstrap_empty_guard (
  is_empty TINYINT NOT NULL,
  CONSTRAINT ck_bootstrap_database_empty CHECK (is_empty = 1)
);
INSERT INTO bootstrap_empty_guard (is_empty)
SELECT NOT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = DATABASE())
   AND NOT EXISTS (SELECT 1 FROM information_schema.routines WHERE routine_schema = DATABASE())
   AND NOT EXISTS (SELECT 1 FROM information_schema.events WHERE event_schema = DATABASE());
DROP TEMPORARY TABLE bootstrap_empty_guard;

-- Schema only. Auto-increment positions and trigger definers are installation-local.
/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!50503 SET NAMES utf8mb4 */;
/*!40103 SET @OLD_TIME_ZONE=@@TIME_ZONE */;
/*!40103 SET TIME_ZONE='+00:00' */;
/*!40014 SET @OLD_UNIQUE_CHECKS=@@UNIQUE_CHECKS, UNIQUE_CHECKS=0 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_chat_message` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `session_id` bigint unsigned NOT NULL,
  `request_id` varchar(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `role` enum('USER','ASSISTANT') COLLATE utf8mb4_bin NOT NULL,
  `content` text COLLATE utf8mb4_bin NOT NULL,
  `events` json DEFAULT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ai_chat_message_request` (`session_id`,`request_id`,`role`),
  KEY `ix_ai_chat_message_history` (`session_id`,`id`),
  CONSTRAINT `fk_ai_chat_message_session` FOREIGN KEY (`session_id`) REFERENCES `ai_chat_session` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `ai_chat_session` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `user_id` int NOT NULL,
  `chat_key` varchar(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  `origin_path` varchar(400) COLLATE utf8mb4_bin NOT NULL,
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_ai_chat_session_owner` (`user_id`,`chat_key`),
  KEY `ix_ai_chat_session_recent` (`user_id`,`updated_at`,`id`),
  CONSTRAINT `fk_ai_chat_session_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `article` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '文章id',
  `user_id` int NOT NULL COMMENT '用户id',
  `tag` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '标签',
  `title` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '标题',
  `description` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '描述',
  `content` mediumtext CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '内容',
  `cover_url` varchar(400) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '封面url',
  `read_count` int NOT NULL DEFAULT '0' COMMENT '阅读量',
  `like_count` int NOT NULL DEFAULT '0' COMMENT '点赞量',
  `comment_count` int NOT NULL DEFAULT '0' COMMENT '评论数',
  `collect_count` int NOT NULL DEFAULT '0' COMMENT '收藏量',
  `examine_status` tinyint NOT NULL DEFAULT '0' COMMENT '审核状态 0-待审核 1-审核通过 2-审核未通过',
  `edit_status` tinyint NOT NULL DEFAULT '0' COMMENT '编辑状态 0-已发布 1-草稿箱 2-回收站',
  `visible_range` tinyint NOT NULL DEFAULT '0' COMMENT '可见范围 0-全部可见 1-仅我可见 2-粉丝可见',
  `reprint_type` tinyint NOT NULL DEFAULT '0' COMMENT '转载类型 0-原创 1-转载',
  `reprint_url` varchar(400) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '转载链接',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 1-已删除',
  `deleted_time` datetime DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_examine_edit_visible_status_create_time` (`examine_status`,`edit_status`,`visible_range`,`create_time`) USING BTREE,
  KEY `idx_examine_edit_visible_status_update_time` (`examine_status`,`edit_status`,`visible_range`,`update_time`) USING BTREE,
  KEY `idx_is_deleted_deleted_time` (`is_deleted`,`deleted_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_0900_ai_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50003 TRIGGER `trg_article_logic_delete_time` BEFORE UPDATE ON `article` FOR EACH ROW BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `article_column` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `sort` int NOT NULL COMMENT '排序',
  `article_id` int NOT NULL COMMENT '文章id',
  `column_id` int NOT NULL COMMENT '专栏id',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 1-已删除',
  `deleted_time` datetime DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_article_id` (`article_id`) USING BTREE,
  KEY `idx_column_id` (`column_id`) USING BTREE,
  KEY `idx_is_deleted_deleted_time` (`is_deleted`,`deleted_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_0900_ai_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50003 TRIGGER `trg_article_column_logic_delete_time` BEFORE UPDATE ON `article_column` FOR EACH ROW BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `article_favorite` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `article_id` int NOT NULL COMMENT '被收藏的文章id',
  `favorite_id` int NOT NULL COMMENT '收藏夹id',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_article_time` (`article_id`,`create_time` DESC) USING BTREE,
  KEY `idx_favorite_time` (`favorite_id`,`create_time` DESC) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `column` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '专栏id',
  `user_id` int NOT NULL COMMENT '用户id',
  `sort` int NOT NULL COMMENT '排序',
  `name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '专栏名称',
  `description` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '专栏描述',
  `cover_url` varchar(400) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '专栏封面',
  `show_status` tinyint NOT NULL DEFAULT '0' COMMENT '展示状态 0-公开 1-私密',
  `examine_status` tinyint NOT NULL DEFAULT '0' COMMENT '审核状态 0-待审核 1-审核通过 2-审核未通过',
  `focus_count` int NOT NULL DEFAULT '0' COMMENT '关注数',
  `article_count` int NOT NULL DEFAULT '0' COMMENT '文章数',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 1-已删除',
  `deleted_time` datetime DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_user_id_examine_status` (`user_id`,`examine_status`) USING BTREE,
  KEY `idx_is_deleted_deleted_time` (`is_deleted`,`deleted_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_0900_ai_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50003 TRIGGER `trg_column_logic_delete_time` BEFORE UPDATE ON `column` FOR EACH ROW BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `comment` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '评论id',
  `parent_id` int DEFAULT '0' COMMENT '父级评论id',
  `article_id` int NOT NULL COMMENT '文章id',
  `user_id` int NOT NULL COMMENT '评论用户id',
  `reply_user_id` int DEFAULT NULL COMMENT '回复的用户id',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '评论内容',
  `examine_status` tinyint NOT NULL DEFAULT '0' COMMENT '审核状态 0-待审核 1-审核通过 2-审核未通过',
  `like_count` int NOT NULL DEFAULT '0' COMMENT '点赞数',
  `reply_count` int NOT NULL DEFAULT '0' COMMENT '回复数',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 1-已删除',
  `deleted_time` datetime DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_article_id_parent_id_examine_status_create_time` (`article_id`,`parent_id`,`examine_status`,`create_time` DESC) USING BTREE,
  KEY `idx_is_deleted_deleted_time` (`is_deleted`,`deleted_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_0900_ai_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50003 TRIGGER `trg_comment_logic_delete_time` BEFORE UPDATE ON `comment` FOR EACH ROW BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `conversation` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `user_id` int NOT NULL COMMENT '用户id',
  `target_user_id` int NOT NULL COMMENT '目标用户id',
  `last_message_id` int DEFAULT NULL COMMENT '最后一条消息id',
  `last_message_content` varchar(400) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '最后一条消息内容',
  `last_message_time` datetime DEFAULT NULL COMMENT '最后消息时间',
  `unread_count` int NOT NULL DEFAULT '0' COMMENT '未读消息数',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-正常 1-删除',
  `deleted_time` datetime DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_user_update` (`user_id`,`update_time` DESC) USING BTREE,
  KEY `idx_is_deleted_deleted_time` (`is_deleted`,`deleted_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_0900_ai_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50003 TRIGGER `trg_conversation_logic_delete_time` BEFORE UPDATE ON `conversation` FOR EACH ROW BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `favorite` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '收藏夹id',
  `user_id` int NOT NULL COMMENT '用户id',
  `name` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '收藏夹名称',
  `show_status` tinyint NOT NULL DEFAULT '0' COMMENT '展示状态 0-公开 1-私密',
  `article_count` int NOT NULL DEFAULT '0' COMMENT '文章数量',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 1-已删除',
  `deleted_time` datetime DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_user_status_time` (`user_id`,`show_status`,`create_time` DESC) USING BTREE,
  KEY `idx_is_deleted_deleted_time` (`is_deleted`,`deleted_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_0900_ai_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50003 TRIGGER `trg_favorite_logic_delete_time` BEFORE UPDATE ON `favorite` FOR EACH ROW BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `follow` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '关注id',
  `follower_id` int NOT NULL COMMENT '关注者用户id',
  `followed_id` int NOT NULL COMMENT '被关注者用户id',
  `create_time` datetime NOT NULL COMMENT '关注时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_follower_time` (`follower_id`,`create_time` DESC) USING BTREE,
  KEY `idx_followed_time` (`followed_id`,`create_time` DESC) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `history` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '历史id',
  `article_id` int NOT NULL COMMENT '文章id',
  `user_id` int NOT NULL COMMENT '用户id',
  `view_time` datetime NOT NULL COMMENT '浏览时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 1-已删除',
  `deleted_time` datetime DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_article_user` (`article_id`,`user_id`) USING BTREE,
  KEY `idx_user_view_time` (`user_id`,`view_time` DESC) USING BTREE,
  KEY `idx_history_hot_articles` (`view_time`,`article_id`,`user_id`) USING BTREE,
  KEY `idx_is_deleted_deleted_time` (`is_deleted`,`deleted_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_0900_ai_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50003 TRIGGER `trg_history_logic_delete_time` BEFORE UPDATE ON `history` FOR EACH ROW BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `knowledge_document` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `title` varchar(160) COLLATE utf8mb4_bin NOT NULL,
  `original_filename` varchar(255) COLLATE utf8mb4_bin NOT NULL,
  `file_path` varchar(160) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `replaced_path` varchar(160) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL,
  `deleting` tinyint(1) NOT NULL DEFAULT '0',
  `file_type` enum('MD','TXT') COLLATE utf8mb4_bin NOT NULL,
  `file_size` int unsigned NOT NULL,
  `file_sha256` binary(32) NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_document_next_file` (`file_sha256`),
  UNIQUE KEY `uq_document_next_path` (`file_path`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `like` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '点赞id',
  `user_id` int NOT NULL COMMENT '点赞用户id',
  `type` tinyint NOT NULL COMMENT '点赞类型 0-文章 1-评论',
  `type_id` int NOT NULL COMMENT '点赞类型id',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_user_type_id` (`user_id`,`type`,`type_id`) USING BTREE,
  KEY `idx_type_id_create_time` (`type`,`type_id`,`create_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `message` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '消息id',
  `is_read` tinyint NOT NULL DEFAULT '0' COMMENT '是否已读 0-未读 1-已读',
  `type` tinyint NOT NULL DEFAULT '0' COMMENT '消息类型 0-系统 1-评论 2-点赞 3-收藏 4-关注',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '消息内容',
  `sender_id` int NOT NULL COMMENT '发送消息的用户id',
  `receiver_id` int NOT NULL COMMENT '接收消息的用户id',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 1-已删除',
  `deleted_time` datetime DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_sender_id_type` (`sender_id`,`type`) USING BTREE,
  KEY `idx_receiver_id_type` (`receiver_id`,`type`) USING BTREE,
  KEY `idx_is_deleted_deleted_time` (`is_deleted`,`deleted_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_0900_ai_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50003 TRIGGER `trg_message_logic_delete_time` BEFORE UPDATE ON `message` FOR EACH ROW BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `photo` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '图片id',
  `user_id` bigint NOT NULL COMMENT '用户id',
  `url` varchar(400) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '图片url',
  `examine_status` tinyint NOT NULL DEFAULT '0' COMMENT '审核状态 0-待审核 1-审核通过 2-审核未通过',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 1-已删除',
  `deleted_time` datetime DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_deleted_id_examine_status` (`is_deleted`,`id`,`examine_status`) USING BTREE,
  KEY `idx_is_deleted_deleted_time` (`is_deleted`,`deleted_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_0900_ai_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50003 TRIGGER `trg_photo_logic_delete_time` BEFORE UPDATE ON `photo` FOR EACH ROW BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `private_message` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `from_user_id` int NOT NULL COMMENT '发送者id',
  `to_user_id` int NOT NULL COMMENT '接收者id',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '消息内容',
  `message_type` tinyint NOT NULL DEFAULT '1' COMMENT '消息类型 1-文本 2-图片',
  `image_url` varchar(400) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '图片url',
  `examine_status` tinyint NOT NULL DEFAULT '0' COMMENT '审核状态：0-待审核 1-通过 2-未通过',
  `is_read` tinyint NOT NULL DEFAULT '0' COMMENT '是否已读 0-未读 1-已读',
  `is_revoked` tinyint NOT NULL DEFAULT '0' COMMENT '是否撤回 0-正常 1-撤回',
  `read_time` datetime DEFAULT NULL COMMENT '阅读时间',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-正常 1-删除',
  `deleted_time` datetime DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_from_to` (`from_user_id`,`to_user_id`,`create_time`) USING BTREE,
  KEY `idx_to_from` (`to_user_id`,`from_user_id`,`create_time`) USING BTREE,
  KEY `idx_is_deleted_deleted_time` (`is_deleted`,`deleted_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_0900_ai_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50003 TRIGGER `trg_private_message_logic_delete_time` BEFORE UPDATE ON `private_message` FOR EACH ROW BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `rag_chunk` (
  `knowledge_id` bigint unsigned NOT NULL,
  `revision` bigint unsigned NOT NULL,
  `chunk_no` int unsigned NOT NULL,
  `content` text COLLATE utf8mb4_bin NOT NULL,
  `qdrant_point_id` char(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (`knowledge_id`,`revision`,`chunk_no`),
  UNIQUE KEY `uq_rag_chunk_point` (`qdrant_point_id`),
  CONSTRAINT `fk_rag_chunk_knowledge` FOREIGN KEY (`knowledge_id`) REFERENCES `rag_knowledge` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `rag_knowledge` (
  `id` bigint unsigned NOT NULL AUTO_INCREMENT,
  `document_id` bigint unsigned DEFAULT NULL,
  `title` varchar(160) COLLATE utf8mb4_bin NOT NULL,
  `content` mediumtext COLLATE utf8mb4_bin NOT NULL,
  `source_url` varchar(400) COLLATE utf8mb4_bin DEFAULT NULL,
  `enabled` tinyint(1) NOT NULL DEFAULT '0',
  `index_status` enum('PENDING','READY','FAILED','DELETING','UNINDEXED') COLLATE utf8mb4_bin NOT NULL DEFAULT 'PENDING',
  `revision` bigint unsigned NOT NULL DEFAULT '0',
  `pending_title` varchar(160) COLLATE utf8mb4_bin DEFAULT NULL,
  `pending_content` mediumtext COLLATE utf8mb4_bin,
  `index_attempts` tinyint unsigned NOT NULL DEFAULT '0',
  `retry_at` datetime(6) DEFAULT NULL,
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  KEY `ix_rag_knowledge_document` (`document_id`),
  KEY `ix_rag_knowledge_pending` (`index_status`,`retry_at`,`id`),
  CONSTRAINT `fk_rag_knowledge_document` FOREIGN KEY (`document_id`) REFERENCES `knowledge_document` (`id`),
  CONSTRAINT `ck_rag_knowledge_pending` CHECK (((`pending_title` is null) = (`pending_content` is null)))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_loginlog` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '登录日志id',
  `user_id` int DEFAULT NULL COMMENT '用户id',
  `username` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用户名',
  `login_type` tinyint DEFAULT '0' COMMENT '登录方式 0-用户名/邮箱',
  `login_ip` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '登录ip',
  `login_address` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '登录地址',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '登录状态 0-成功 1-失败',
  `login_time` datetime NOT NULL COMMENT '登录时间',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_menu` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '菜单id',
  `parent_id` int DEFAULT '0' COMMENT '父级id',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '菜单名称',
  `sort` int DEFAULT '0' COMMENT '排序',
  `path` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '路由路径',
  `component` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '组件路径',
  `icon` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '图标',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态 0-正常 1-禁用',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `is_deleted` tinyint DEFAULT '0' COMMENT '是否删除 0-未删除 1-已删除',
  `deleted_time` datetime DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_is_deleted_deleted_time` (`is_deleted`,`deleted_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_0900_ai_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50003 TRIGGER `trg_sys_menu_logic_delete_time` BEFORE UPDATE ON `sys_menu` FOR EACH ROW BEGIN
    IF IFNULL(OLD.`is_deleted`, 0) = 0 AND IFNULL(NEW.`is_deleted`, 0) = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF IFNULL(OLD.`is_deleted`, 0) = 1 AND IFNULL(NEW.`is_deleted`, 0) = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_operationlog` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `module` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '功能模块',
  `operation` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '操作类型',
  `description` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '操作描述',
  `method` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '请求方法 (类名：方法名)',
  `request_method` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '请求方式 (get/post/put/delete)',
  `request_url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '请求 url',
  `request_param` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '请求参数',
  `response_result` text CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci COMMENT '返回结果',
  `operator_id` int NOT NULL COMMENT '操作人员 id',
  `operator_role` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '操作人员角色',
  `operator_name` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '操作人员名字',
  `ip` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '操作 ip',
  `address` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '操作地址',
  `time` bigint DEFAULT '0' COMMENT '消耗时间 (ms)',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '操作状态 0-成功 1-失败 2-异常',
  `exception` varchar(2000) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '异常消息',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-正常 1-删除',
  `deleted_time` datetime DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_operator_time` (`operator_id`,`create_time` DESC) USING BTREE,
  KEY `idx_is_deleted_deleted_time` (`is_deleted`,`deleted_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_0900_ai_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50003 TRIGGER `trg_sys_operationlog_logic_delete_time` BEFORE UPDATE ON `sys_operationlog` FOR EACH ROW BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_permission` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '权限id',
  `description` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '权限描述',
  `permission` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '权限',
  `menu_id` int NOT NULL COMMENT '菜单id',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `is_deleted` tinyint NOT NULL DEFAULT '0' COMMENT '是否删除 0-未删除 1-已删除',
  `deleted_time` datetime DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_is_deleted_deleted_time` (`is_deleted`,`deleted_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_0900_ai_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50003 TRIGGER `trg_sys_permission_logic_delete_time` BEFORE UPDATE ON `sys_permission` FOR EACH ROW BEGIN
    IF OLD.`is_deleted` = 0 AND NEW.`is_deleted` = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF OLD.`is_deleted` = 1 AND NEW.`is_deleted` = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '角色id',
  `role` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '角色标识',
  `name` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '角色名称',
  `description` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '角色描述',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态 0-正常 1-禁用',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `is_deleted` tinyint DEFAULT '0' COMMENT '是否删除 0-未删除 1-已删除',
  `deleted_time` datetime DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_is_deleted_deleted_time` (`is_deleted`,`deleted_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_0900_ai_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50003 TRIGGER `trg_sys_role_logic_delete_time` BEFORE UPDATE ON `sys_role` FOR EACH ROW BEGIN
    IF IFNULL(OLD.`is_deleted`, 0) = 0 AND IFNULL(NEW.`is_deleted`, 0) = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF IFNULL(OLD.`is_deleted`, 0) = 1 AND IFNULL(NEW.`is_deleted`, 0) = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_menu` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `role_id` int NOT NULL COMMENT '角色id',
  `menu_id` int NOT NULL COMMENT '菜单id',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_role_id` (`role_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_role_permission` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `role_id` int NOT NULL COMMENT '角色id',
  `permission_id` int NOT NULL COMMENT '权限id',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_role_id` (`role_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '用户id',
  `username` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '用户名',
  `password` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '密码',
  `nickname` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '昵称',
  `email` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '邮箱',
  `sex` tinyint DEFAULT '0' COMMENT '性别 0-男 1-女',
  `introduction` varchar(200) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '简介',
  `avatar` varchar(400) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '头像',
  `status` tinyint NOT NULL DEFAULT '0' COMMENT '状态 0-正常 1-禁用',
  `fans_count` int DEFAULT '0' COMMENT '粉丝数',
  `follow_count` int DEFAULT '0' COMMENT '关注数',
  `register_type` tinyint DEFAULT '0' COMMENT '注册方式 0-用户名/邮箱',
  `register_ip` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '注册ip',
  `register_address` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '注册地址',
  `login_type` tinyint DEFAULT '0' COMMENT '登录方式 0-用户名/邮箱',
  `login_ip` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '登录ip',
  `login_address` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '登录地址',
  `login_time` datetime DEFAULT NULL COMMENT '登录时间',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `is_deleted` tinyint DEFAULT '0' COMMENT '是否删除 0-未删除 1-已删除',
  `deleted_time` datetime DEFAULT NULL /*!80023 INVISIBLE */ COMMENT '逻辑删除时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_is_deleted_deleted_time` (`is_deleted`,`deleted_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!50003 SET @saved_cs_client      = @@character_set_client */ ;
/*!50003 SET @saved_cs_results     = @@character_set_results */ ;
/*!50003 SET @saved_col_connection = @@collation_connection */ ;
/*!50003 SET character_set_client  = utf8mb4 */ ;
/*!50003 SET character_set_results = utf8mb4 */ ;
/*!50003 SET collation_connection  = utf8mb4_0900_ai_ci */ ;
/*!50003 SET @saved_sql_mode       = @@sql_mode */ ;
/*!50003 SET sql_mode              = 'ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION' */ ;
DELIMITER ;;
/*!50003 CREATE*/ /*!50003 TRIGGER `trg_sys_user_logic_delete_time` BEFORE UPDATE ON `sys_user` FOR EACH ROW BEGIN
    IF IFNULL(OLD.`is_deleted`, 0) = 0 AND IFNULL(NEW.`is_deleted`, 0) = 1 THEN
        SET NEW.`deleted_time` = CURRENT_TIMESTAMP;
    ELSEIF IFNULL(OLD.`is_deleted`, 0) = 1 AND IFNULL(NEW.`is_deleted`, 0) = 0 THEN
        SET NEW.`deleted_time` = NULL;
    END IF;
END */;;
DELIMITER ;
/*!50003 SET sql_mode              = @saved_sql_mode */ ;
/*!50003 SET character_set_client  = @saved_cs_client */ ;
/*!50003 SET character_set_results = @saved_cs_results */ ;
/*!50003 SET collation_connection  = @saved_col_connection */ ;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_user_role` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键id',
  `user_id` int NOT NULL COMMENT '用户id',
  `role_id` int NOT NULL COMMENT '角色id',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `sys_visitorlog` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '访客记录id',
  `user_id` int DEFAULT NULL COMMENT '访客用户id',
  `ip` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '访客ip',
  `address` varchar(100) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '访客地址',
  `device` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci DEFAULT NULL COMMENT '设备类型（PC/Mobile）',
  `visit_time` datetime NOT NULL COMMENT '访问时间',
  PRIMARY KEY (`id`) USING BTREE,
  KEY `idx_visit_time` (`visit_time`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `tag` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '标签id',
  `category` varchar(10) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '标签分类',
  `name` varchar(30) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci NOT NULL COMMENT '标签名称',
  `sort` int NOT NULL DEFAULT '0' COMMENT '排序',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `idx_name` (`name`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `user_settings` (
  `id` int NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
  `user_id` int NOT NULL COMMENT '用户 ID',
  `receive_private_message_email` tinyint NOT NULL DEFAULT '0' COMMENT '是否接收私信邮件通知：0-关闭，1-开启',
  `receive_comment_email` tinyint NOT NULL DEFAULT '0' COMMENT '是否接收评论邮件通知：0-关闭，1-开启',
  `receive_system_email` tinyint NOT NULL DEFAULT '0' COMMENT '是否接收系统邮件通知：0-关闭，1-开启',
  PRIMARY KEY (`id`) USING BTREE,
  UNIQUE KEY `uk_user_id` (`user_id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci ROW_FORMAT=DYNAMIC COMMENT='用户个人设置表';
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wiki_page` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(160) COLLATE utf8mb4_bin NOT NULL,
  `summary` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `content` mediumtext COLLATE utf8mb4_bin NOT NULL,
  `status` enum('DRAFT','PUBLISHED','OFFLINE') COLLATE utf8mb4_bin NOT NULL DEFAULT 'DRAFT',
  `auto_knowledge_id` bigint unsigned DEFAULT NULL,
  `manually_edited` tinyint(1) NOT NULL DEFAULT '0',
  `review_required` tinyint(1) NOT NULL DEFAULT '0',
  `updated_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  `published_at` datetime(6) DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uq_wiki_page_next_automatic` (`auto_knowledge_id`),
  KEY `ix_wiki_page_next_status` (`status`,`id`),
  CONSTRAINT `fk_wiki_page_next_automatic` FOREIGN KEY (`auto_knowledge_id`) REFERENCES `rag_knowledge` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wiki_page_revision` (
  `wiki_page_id` bigint NOT NULL,
  `version` int unsigned NOT NULL,
  `title` varchar(160) COLLATE utf8mb4_bin NOT NULL,
  `summary` varchar(500) COLLATE utf8mb4_bin NOT NULL,
  `content` mediumtext COLLATE utf8mb4_bin NOT NULL,
  `created_at` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`wiki_page_id`,`version`),
  CONSTRAINT `fk_wiki_page_revision_page` FOREIGN KEY (`wiki_page_id`) REFERENCES `wiki_page` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40101 SET @saved_cs_client     = @@character_set_client */;
/*!50503 SET character_set_client = utf8mb4 */;
CREATE TABLE `wiki_page_source` (
  `wiki_page_id` bigint NOT NULL,
  `knowledge_id` bigint unsigned NOT NULL,
  PRIMARY KEY (`wiki_page_id`,`knowledge_id`),
  KEY `fk_wiki_page_source_knowledge` (`knowledge_id`),
  CONSTRAINT `fk_wiki_page_source_knowledge` FOREIGN KEY (`knowledge_id`) REFERENCES `rag_knowledge` (`id`),
  CONSTRAINT `fk_wiki_page_source_page` FOREIGN KEY (`wiki_page_id`) REFERENCES `wiki_page` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;
/*!40101 SET character_set_client = @saved_cs_client */;
/*!40103 SET TIME_ZONE=@OLD_TIME_ZONE */;

/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40014 SET UNIQUE_CHECKS=@OLD_UNIQUE_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;
/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;

-- Application roles, menus and permissions. The installer creates the two local demo accounts.
SET NAMES utf8mb4;
START TRANSACTION;
-- sys_role
INSERT INTO `sys_role` (`id`, `role`, `name`, `description`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (1,'admin','超级管理员','拥有最高管理权限',0,'2025-06-28 22:31:00','2025-08-10 00:21:12',0,NULL);
INSERT INTO `sys_role` (`id`, `role`, `name`, `description`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (2,'content_admin','内容管理员','负责图片、文章、专栏、标签和评论等内容管理',0,'2025-08-08 15:51:55','2025-08-10 03:06:55',0,NULL);
INSERT INTO `sys_role` (`id`, `role`, `name`, `description`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (3,'user','网站普通用户','博客网站普通用户',0,'2025-08-08 15:52:58','2025-08-09 22:22:07',0,NULL);

-- sys_menu
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (1,0,'首页',0,'/home','/home','House',0,'2025-06-28 22:31:52','2025-08-12 00:34:19',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (2,0,'系统管理',1,'/system',' ','Setting',0,'2025-08-06 15:26:06','2025-08-11 01:21:58',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (3,2,'菜单管理',0,'/system/menu','/system/menu','Menu',0,'2025-08-06 15:40:22','2025-08-11 01:22:00',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (4,2,'角色管理',2,'/system/role','/system/role','Avatar',0,'2025-08-06 15:41:35','2025-08-11 01:21:52',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (5,2,'权限管理',1,'/system/permission','/system/permission','Lock',0,'2025-08-06 15:42:21','2025-08-11 01:22:03',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (6,2,'用户管理',3,'/system/user','/system/user','User',0,'2025-08-06 15:43:41','2025-08-11 01:21:50',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (7,0,'图片管理',3,'/photo',' ','Camera',0,'2025-08-08 16:17:18','2025-08-15 00:24:54',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9,7,'图片审核',1,'/photo/examine','/photo/examine','PictureRounded',0,'2025-08-14 11:42:12','2025-08-15 00:25:35',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (10,0,'文章管理',4,'/article','/article','Document',0,'2025-08-08 16:17:43','2025-08-29 11:42:39',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (11,10,'用户文章',0,'/article/user','/article/user','DocumentCopy',0,'2025-08-29 14:02:23','2025-08-29 14:05:12',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (12,10,'文章审核',1,'/article/examine','/article/examine','DocumentChecked',0,'2025-08-29 14:04:49','2025-08-29 14:05:25',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (13,0,'评论管理',4,'/comment','/comment','ChatDotRound',0,'2025-09-16 23:36:25','2025-09-16 23:36:50',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (14,13,'用户评论',0,'/comment/user','/comment/user','UserFilled',0,'2025-09-16 23:37:50','2025-09-16 23:37:50',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (15,13,'评论审核',1,'/comment/examine','/comment/examine','Comment',0,'2025-09-16 23:38:31','2025-09-16 23:38:31',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (16,0,'专栏管理',5,'/column','/column','Collection',0,'2025-09-21 20:15:57','2025-09-21 20:15:57',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (17,16,'用户专栏',0,'/column/user','/column/user','Folder',0,'2025-09-21 20:16:01','2025-09-21 20:16:01',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (18,16,'专栏审核',1,'/column/examine','/column/examine','FolderChecked',0,'2025-09-21 20:16:04','2025-09-21 20:16:04',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (21,0,'标签管理',8,'/tag','/tag','CollectionTag',0,'2025-10-05 13:20:54','2025-10-05 13:20:54',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (22,2,'登录日志',5,'/system/loginLog','/system/loginLog','Calendar',0,'2025-10-06 19:31:12','2025-10-08 18:09:05',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (23,2,'访客日志',6,'/system/visitorLog','/system/visitorLog','DataAnalysis',0,'2025-10-07 11:15:51','2025-10-08 18:09:08',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (24,2,'操作日志',7,'/system/operationLog','/system/operationLog','Operation',0,'2026-03-09 14:54:05','2026-03-09 14:54:29',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (26,0,'通知管理',2,'/notification',' ','Bell',0,'2026-03-30 00:00:00','2026-03-31 16:17:13',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (28,26,'系统通知管理',1,'/notification/systemNotification','/notification/systemNotification','ChatDotRound',0,'2026-03-30 00:00:00','2026-03-30 00:00:00',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9300,0,'AI知识管理',9,'/ai',' ','Cpu',0,'2026-10-30 16:53:16','2026-10-30 16:53:16',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9301,9300,'RAG 知识库',1,'/ai/rag','/ai/library','Collection',0,'2026-10-30 16:53:16','2026-11-11 11:02:25',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9302,9300,'Wiki 页面',2,'/ai/wiki','/ai/library','Document',0,'2026-10-30 16:53:16','2026-11-11 11:02:25',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9303,9300,'知识来源',1,'/ai/sources','/ai/sources','Link',1,'2026-10-30 16:53:16','2026-11-11 11:02:25',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9304,9300,'智能整理',2,'/ai/tasks','/ai/tasks','MagicStick',1,'2026-10-30 16:53:16','2026-11-11 11:02:25',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9305,9300,'检索验证',3,'/ai/search','/ai/library','Search',0,'2026-10-30 16:53:16','2026-11-11 11:02:25',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9306,9300,'AI 配置',4,'/ai/config','/ai/library','Setting',0,'2026-11-03 22:01:19','2026-11-11 11:02:25',0,NULL);
INSERT INTO `sys_menu` (`id`, `parent_id`, `name`, `sort`, `path`, `component`, `icon`, `status`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9370,9300,'知识文档',0,'/ai/documents','/ai/library','Files',0,'2026-11-10 20:09:22','2026-11-11 11:02:25',0,NULL);

-- sys_permission
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (1,'获取用户菜单','backend:menu:list',3,'2025-08-06 15:57:43','2025-08-06 15:57:45',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (2,'获取菜单列表','system:menu:listAll',3,'2025-08-08 20:27:02','2025-08-08 20:27:06',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (3,'新增菜单','system:menu:add',3,'2025-08-06 16:20:13','2025-08-06 16:20:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (4,'修改菜单','system:menu:update',3,'2025-08-06 16:20:13','2025-08-06 16:20:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (5,'删除菜单','system:menu:delete',3,'2025-08-06 16:20:13','2025-08-06 16:20:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (6,'搜索菜单','system:menu:search',3,'2025-08-08 03:06:41','2025-08-08 03:06:43',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (7,'获取角色列表','system:role:list',4,'2025-08-06 15:57:43','2025-08-06 15:57:45',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (8,'新增角色','system:role:add',4,'2025-08-06 16:20:13','2025-08-06 16:20:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9,'修改角色','system:role:update',4,'2025-08-06 16:20:13','2025-08-06 16:20:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (10,'删除角色','system:role:delete',4,'2025-08-06 16:20:13','2025-08-06 16:20:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (11,'搜索角色','system:role:search',4,'2025-08-08 03:06:41','2025-08-08 03:06:43',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (12,'菜单分配角色','system:role:menu:add',3,'2025-08-09 23:42:07','2025-08-09 23:42:09',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (13,'获取菜单所属角色','system:role:menu:get',3,'2025-08-10 00:01:37','2025-08-10 00:01:40',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (14,'角色分配用户','system:user:role:addUser',4,'2025-08-10 11:36:31','2025-08-10 11:36:34',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (15,'获取角色所属用户','system:user:role:getUsers',4,'2025-08-10 11:38:00','2025-08-10 11:38:02',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (16,'获取用户列表','system:user:list',6,'2025-08-10 12:32:02','2025-08-10 12:32:06',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (17,'用户添加角色','system:user:role:addRole',6,'2025-08-10 15:33:16','2025-08-10 15:33:19',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (18,'获取用户所属角色','system:user:role:getRoles',6,'2025-08-10 15:33:58','2025-08-10 15:34:01',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (19,'获取权限列表','system:permission:list',5,'2025-08-10 16:56:44','2025-08-10 16:56:47',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (20,'新增权限','system:permission:add',5,'2025-08-10 16:57:55','2025-08-10 16:57:57',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (21,'修改权限','system:permission:update',5,'2025-08-10 16:58:13','2025-08-10 16:58:15',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (22,'删除权限','system:permission:delete',5,'2025-08-10 16:58:26','2025-08-10 16:58:29',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (23,'搜索权限','system:permission:search',5,'2025-08-10 16:58:55','2025-08-10 16:58:57',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (24,'权限授权角色','system:role:permission:add',5,'2025-08-10 17:10:12','2025-08-10 17:10:15',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (25,'获取权限所属角色','system:role:permission:get',5,'2025-08-10 17:10:55','2025-08-10 17:10:57',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (26,'权限批量授权角色','system:role:permission:addBatch',5,'2025-08-11 02:22:50','2025-08-11 02:22:52',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (27,'获取用户列表','system:user:list',6,'2025-08-11 10:59:07','2025-08-11 10:59:07',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (28,'修改用户','system:user:update',6,'2025-08-11 13:27:07','2025-08-11 13:27:07',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (29,'删除用户','system:user:delete',6,'2025-08-11 13:27:29','2025-08-11 13:27:29',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (30,'搜索用户','system:user:search',6,'2025-08-11 17:29:26','2025-08-11 17:29:26',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (31,'获取用户详情','system:user:info',6,'2025-08-11 18:18:41','2025-08-11 18:18:41',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (37,'删除图片','photo:delete',9,'2025-08-17 00:30:04','2025-08-17 00:30:04',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (38,'批量删除图片','photo:deleteBatch',9,'2025-08-17 00:30:15','2025-08-17 00:30:15',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (39,'审核图片','photo:audit',9,'2025-08-17 00:39:51','2025-08-17 00:39:51',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (40,'批量审核图片','photo:auditBatch',9,'2025-08-17 01:45:58','2025-08-17 01:45:58',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (41,'获取图片列表','photo:list',9,'2025-08-17 03:10:00','2025-08-17 03:10:00',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (42,'搜索图片','photo:search',9,'2025-08-17 15:19:06','2025-08-17 15:19:06',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (43,'获取未读消息数量','message:count',1,'2025-08-18 02:01:25','2025-08-21 00:17:46',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (44,'获取消息列表','message:list',1,'2025-08-18 02:01:40','2025-08-18 02:01:40',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (45,'读取消息','message:read',1,'2025-08-21 00:15:34','2025-08-21 00:17:28',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (46,'删除消息','message:delete',1,'2025-08-21 02:36:08','2025-08-21 02:36:08',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (47,'获取文章列表','article:list',11,'2025-09-03 19:40:08','2025-09-03 19:40:08',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (48,'获取文章详情','article:get',11,'2025-09-03 19:43:16','2025-09-03 19:43:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (49,'修改文章','article:update',11,'2025-09-03 19:44:41','2025-09-03 19:44:41',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (50,'删除文章','article:delete',11,'2025-09-03 19:45:01','2025-09-03 19:45:01',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (51,'审核文章','article:examine',12,'2025-09-03 19:45:25','2025-09-03 19:45:25',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (52,'搜索文章','article:search',12,'2025-09-04 14:09:16','2025-09-04 14:09:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (53,'获取用户文章列表','article:user:list',11,'2025-09-04 15:47:30','2025-09-04 15:47:30',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (54,'获取评论列表','comment:list',15,'2025-09-19 22:20:08','2025-09-19 22:20:08',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (55,'搜索评论','comment:search',15,'2025-09-19 22:20:52','2025-09-19 22:20:52',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (56,'审核评论','comment:examine',15,'2025-09-19 22:21:20','2025-09-19 22:50:25',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (57,'删除评论','comment:delete',15,'2025-09-19 22:21:37','2025-09-19 22:21:37',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (58,'获取用户评论列表','comment:user:list',14,'2025-09-19 22:22:09','2025-09-19 22:22:09',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (59,'获取专栏列表','column:list',17,'2025-09-21 20:16:17','2025-09-21 20:16:17',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (60,'获取用户专栏列表','column:user:list',17,'2025-09-21 20:16:21','2025-09-21 20:16:21',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (61,'搜索专栏','column:search',17,'2025-09-21 20:16:25','2025-09-21 20:16:25',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (62,'审核专栏','column:examine',18,'2025-09-21 20:16:29','2025-09-21 20:16:29',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (63,'删除专栏','column:delete',17,'2025-09-21 20:16:33','2025-09-21 20:16:33',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (64,'更新专栏','column:update',17,'2025-09-23 18:47:16','2025-09-23 18:47:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (65,'获取专栏详情','column:detail',17,'2025-09-24 08:40:20','2025-09-24 08:40:20',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (75,'新增标签','tag:add',21,'2025-10-06 14:03:48','2025-10-06 14:03:48',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (76,'修改标签','tag:update',21,'2025-10-06 14:05:59','2025-10-06 14:42:21',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (77,'删除标签','tag:delete',21,'2025-10-06 14:42:31','2025-10-06 14:42:31',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (78,'获取登录日志','system:loginLog:list',22,'2025-10-06 19:32:18','2025-10-06 19:33:24',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (79,'搜索日志','system:loginLog:search',22,'2025-10-06 19:32:39','2025-10-06 19:32:39',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (80,'删除登录日志','system:loginLog:delete',22,'2025-10-06 19:33:39','2025-10-06 19:33:39',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (81,'获取访客列表','system:visitorLog:list',23,'2025-10-07 11:14:35','2025-10-07 11:16:07',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (82,'搜索访客日志','system:visitorLog:search',23,'2025-10-07 15:46:53','2025-10-07 15:47:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (83,'删除访客日志','system:visitorLog:delete',23,'2025-10-07 15:47:11','2025-10-07 15:47:11',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (84,'获取操作日志','system:operationlog:list',24,'2026-03-09 15:59:15','2026-03-09 15:59:15',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (85,'搜索操作日志','system:operationlog:search',24,'2026-03-09 15:59:38','2026-03-09 15:59:38',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (86,'删除操作日志','system:operationlog:delete',24,'2026-03-09 15:59:52','2026-03-09 15:59:52',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (93,'获取 Dashboard 统计数据','backend:dashboard:list',1,'2026-03-15 15:49:58','2026-03-15 15:49:58',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (94,'刷新 Dashboard 缓存','admin:dashboard:refresh',1,'2026-03-15 15:49:58','2026-03-15 15:49:58',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (95,'角色分配菜单权限','system:role:menu:assign',4,'2026-03-28 17:36:09','2026-03-28 17:36:09',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (100,'获取通知列表','message:list',28,'2026-03-30 00:00:00','2026-03-30 00:00:00',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (101,'标记通知未读','message:unread',1,'2026-06-11 00:00:00','2026-06-11 00:00:00',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9310,'查看知识库','ai:rag:read',9301,'2026-10-30 16:53:16','2026-10-30 16:53:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9311,'管理知识库','ai:rag:manage',9301,'2026-10-30 16:53:16','2026-10-30 16:53:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9320,'查看Wiki页面','ai:wiki:read',9302,'2026-10-30 16:53:16','2026-10-30 16:53:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9321,'编辑Wiki页面','ai:wiki:edit',9302,'2026-10-30 16:53:16','2026-10-30 16:53:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9322,'审核Wiki页面','ai:wiki:review',9302,'2026-10-30 16:53:16','2026-10-30 16:53:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9323,'发布Wiki页面','ai:wiki:publish',9302,'2026-10-30 16:53:16','2026-10-30 16:53:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9330,'查看知识来源','ai:wiki:read',9303,'2026-10-30 16:53:16','2026-10-30 16:53:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9331,'授权及撤销来源','ai:source:manage',9303,'2026-10-30 16:53:16','2026-10-30 16:53:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9340,'查看整理任务','ai:wiki:read',9304,'2026-10-30 16:53:16','2026-10-30 16:53:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9341,'发起及取消整理','ai:wiki:compile',9304,'2026-10-30 16:53:16','2026-10-30 16:53:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9342,'保存整理草稿','ai:wiki:edit',9304,'2026-10-30 16:53:16','2026-10-30 16:53:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9350,'检索及来源验证','ai:knowledge:test',9305,'2026-10-30 16:53:16','2026-10-30 16:53:16',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9360,'查看AI配置','ai:config:read',9306,'2026-11-03 22:01:19','2026-11-03 22:01:19',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9361,'修改AI配置','ai:config:manage',9306,'2026-11-03 22:01:19','2026-11-03 22:01:19',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9371,'查看知识文档','ai:rag:read',9370,'2026-11-10 20:09:22','2026-11-10 20:09:22',0,NULL);
INSERT INTO `sys_permission` (`id`, `description`, `permission`, `menu_id`, `create_time`, `update_time`, `is_deleted`, `deleted_time`) VALUES (9372,'管理知识文档','ai:rag:manage',9370,'2026-11-10 20:09:22','2026-11-10 20:09:22',0,NULL);

-- sys_role_menu
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (1,1,1);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (2,1,2);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (3,1,3);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (4,1,4);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (5,1,5);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (20,1,6);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (26,1,7);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (30,1,9);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (31,1,10);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (32,1,11);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (33,1,12);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (35,1,13);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (36,1,14);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (37,1,15);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (38,1,16);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (39,1,17);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (40,1,18);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (55,1,21);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (56,1,22);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (57,1,23);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (63,1,24);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (68,1,26);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (70,1,28);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (74,2,1);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (75,2,7);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (76,2,9);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (77,2,10);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (78,2,11);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (79,2,12);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (80,2,13);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (81,2,14);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (82,2,15);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (83,2,16);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (84,2,17);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (85,2,18);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (86,2,21);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (87,2,26);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (88,2,28);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (96,1,9300);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (97,1,9301);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (98,1,9302);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (99,1,9303);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (100,1,9304);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (101,1,9305);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (102,1,9306);
INSERT INTO `sys_role_menu` (`id`, `role_id`, `menu_id`) VALUES (106,1,9370);

-- sys_role_permission
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (1,1,1);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (2,1,2);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (3,1,3);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (4,1,4);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (5,1,5);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (6,1,6);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (10,1,7);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (11,1,8);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (12,1,9);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (14,1,11);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (17,1,12);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (18,1,13);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (19,1,14);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (20,1,15);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (21,1,16);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (22,1,17);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (23,1,18);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (24,1,19);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (25,1,20);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (26,1,21);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (27,1,22);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (28,1,23);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (29,1,24);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (30,1,25);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (31,1,26);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (49,3,26);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (53,1,27);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (54,1,28);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (55,1,29);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (56,1,30);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (57,1,31);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (63,1,39);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (64,1,38);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (65,1,37);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (66,1,40);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (67,1,41);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (68,1,42);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (69,1,43);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (70,1,44);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (71,1,45);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (72,1,46);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (73,1,47);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (74,1,49);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (75,1,51);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (76,1,50);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (77,1,48);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (78,1,52);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (79,1,53);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (80,1,54);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (81,1,55);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (82,1,56);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (83,1,57);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (84,1,58);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (85,1,59);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (86,1,60);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (87,1,61);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (88,1,62);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (89,1,63);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (111,1,64);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (112,1,65);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (123,1,75);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (124,1,76);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (125,1,77);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (126,1,78);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (127,1,79);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (128,1,80);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (129,1,81);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (130,1,82);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (131,1,83);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (142,1,84);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (144,1,85);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (146,1,86);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (159,1,93);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (160,1,94);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (163,1,95);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (168,1,100);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (169,2,1);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (170,2,37);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (171,2,38);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (172,2,39);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (173,2,40);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (174,2,41);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (175,2,42);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (176,2,43);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (177,2,44);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (178,2,45);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (180,2,47);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (181,2,48);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (182,2,49);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (183,2,50);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (184,2,51);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (185,2,52);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (186,2,53);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (187,2,54);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (188,2,55);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (189,2,56);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (190,2,57);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (191,2,58);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (192,2,59);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (193,2,60);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (194,2,61);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (195,2,62);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (196,2,63);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (197,2,64);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (198,2,65);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (199,2,75);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (200,2,76);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (201,2,77);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (202,2,93);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (204,1,101);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (205,2,46);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (206,2,101);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (222,1,9310);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (223,1,9311);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (224,1,9320);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (225,1,9321);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (226,1,9322);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (227,1,9323);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (228,1,9330);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (229,1,9331);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (230,1,9340);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (231,1,9341);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (232,1,9342);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (233,1,9350);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (234,2,9310);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (235,2,9311);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (236,2,9320);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (237,2,9321);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (238,2,9322);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (239,2,9323);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (240,2,9330);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (241,2,9331);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (242,2,9340);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (243,2,9341);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (244,2,9342);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (245,2,9350);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (246,2,9360);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (247,1,9360);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (248,2,9361);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (249,1,9361);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (250,2,9371);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (251,1,9371);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (252,2,9372);
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES (253,1,9372);
COMMIT;
