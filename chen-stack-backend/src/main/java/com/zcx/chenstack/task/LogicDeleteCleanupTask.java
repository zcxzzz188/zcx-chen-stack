package com.zcx.chenstack.task;

import com.zcx.chenstack.config.ChenStackConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 逻辑删除数据定期物理清理任务
 *
 * @author zcx
 * @since 2026-06-12
 */
@Component
@Slf4j
public class LogicDeleteCleanupTask {

    private static final int DEFAULT_RETENTION_DAYS = 7;
    private static final int DEFAULT_BATCH_SIZE = 500;
    private static final int MIN_RETENTION_DAYS = 1;
    private static final int MIN_BATCH_SIZE = 1;
    private static final int MAX_BATCH_SIZE = 2000;
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");
    private static final List<String> CLEANUP_TABLES = List.of(
            "`article_column`",
            "`comment`",
            "`history`",
            "`favorite`",
            "`message`",
            "`private_message`",
            "`conversation`",
            "`photo`",
            "`article`",
            "`column`",
            "`sys_operationlog`",
            "`sys_permission`",
            "`sys_menu`",
            "`sys_role`",
            "`sys_user`"
    );

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private ChenStackConfig chenStackConfig;

    @Scheduled(cron = "${chen-stack.logic-delete-cleanup.cron:0 30 3 * * ?}", zone = "Asia/Shanghai")
    public void cleanLogicDeletedData() {
        ChenStackConfig.LogicDeleteCleanupConfig cleanupConfig = chenStackConfig.getLogicDeleteCleanup();
        if (!cleanupConfig.isEnabled()) {
            log.info("逻辑删除物理清理任务已禁用，跳过执行");
            return;
        }

        long startTime = System.currentTimeMillis();
        int retentionDays = sanitizeRetentionDays(cleanupConfig.getRetentionDays());
        int batchSize = sanitizeBatchSize(cleanupConfig.getBatchSize());
        LocalDateTime cutoff = LocalDateTime.now(SHANGHAI_ZONE).minusDays(retentionDays);
        long totalDeleted = 0L;

        log.info("逻辑删除物理清理开始，retentionDays={}，cutoff={}，batchSize={}", retentionDays, cutoff, batchSize);

        try {
            for (String tableName : CLEANUP_TABLES) {
                int deletedCount;
                try {
                    deletedCount = deleteTableInBatches(tableName, cutoff, batchSize);
                } catch (Exception e) {
                    log.error("逻辑删除物理清理失败，table={}，retentionDays={}，cutoff={}，batchSize={}，已删除总数={}，耗时={}ms",
                            tableName, retentionDays, cutoff, batchSize, totalDeleted,
                            System.currentTimeMillis() - startTime, e);
                    return;
                }

                totalDeleted += deletedCount;
                log.info("逻辑删除物理清理表完成，table={}，deletedCount={}", tableName, deletedCount);
            }

            log.info("逻辑删除物理清理完成，总删除数量={}，总耗时={}ms", totalDeleted, System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            log.error("逻辑删除物理清理任务异常，已删除总数={}，耗时={}ms",
                    totalDeleted, System.currentTimeMillis() - startTime, e);
        }
    }

    private int deleteTableInBatches(String tableName, LocalDateTime cutoff, int batchSize) {
        String sql = """
                DELETE FROM %s
                WHERE `is_deleted` = 1
                  AND `deleted_time` IS NOT NULL
                  AND `deleted_time` < ?
                ORDER BY `deleted_time` ASC, `id` ASC
                LIMIT %d
                """.formatted(tableName, batchSize);

        int totalDeleted = 0;
        while (true) {
            int deletedCount = jdbcTemplate.update(sql, Timestamp.valueOf(cutoff));
            totalDeleted += deletedCount;
            if (deletedCount < batchSize) {
                return totalDeleted;
            }
        }
    }

    private int sanitizeRetentionDays(Integer retentionDays) {
        if (retentionDays == null || retentionDays < MIN_RETENTION_DAYS) {
            return DEFAULT_RETENTION_DAYS;
        }
        return retentionDays;
    }

    private int sanitizeBatchSize(Integer batchSize) {
        if (batchSize == null || batchSize < MIN_BATCH_SIZE || batchSize > MAX_BATCH_SIZE) {
            return DEFAULT_BATCH_SIZE;
        }
        return batchSize;
    }

}
