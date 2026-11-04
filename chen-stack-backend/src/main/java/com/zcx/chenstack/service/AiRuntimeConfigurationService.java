package com.zcx.chenstack.service;

import com.zcx.chenstack.config.AiKnowledgeProperties;
import com.zcx.chenstack.config.RagProperties;
import com.zcx.chenstack.rag.KnowledgeModeSource;
import com.zcx.chenstack.rag.RagKnowledgeService;
import com.zcx.chenstack.wiki.WikiProductException;
import com.zcx.chenstack.wiki.WikiPublicationService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * 正式小辰的运行配置：知识模式与每日额度。
 *
 * <p>数据库是持久权威，部署环境变量只提供默认值；内存中的实际生效值每5秒收敛一次，
 * 管理员保存后当前实例立即应用。模式只改变知识依据，不扩大身份、业务工具或后台权限。</p>
 */
@Service
@Slf4j
public class AiRuntimeConfigurationService implements KnowledgeModeSource {
    public static final String MODE_KEY = "assistant.knowledge-mode";
    public static final String QUOTA_KEY = "quota.daily-limit";
    /** 产品可选模式；顺序即后台展示顺序。 */
    public static final List<String> MODES = List.of("rag", "wiki", "hybrid");
    /** 历史配置值：仍可读取和执行，但不再作为可选产品模式。 */
    public static final String LEGACY_CHAT = "chat";
    public static final int MIN_DAILY_LIMIT = 1;
    public static final int MAX_DAILY_LIMIT = 100000;
    private static final long REFRESH_NANOS = TimeUnit.SECONDS.toNanos(5);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    public record ModeAvailability(String mode, String label, boolean available, String note) { }
    public record Change(String label, String oldValue, String newValue, String operator, String changedAt) { }
    public record Status(String effectiveMode, String desiredMode, boolean applied, String applyError,
                         int dailyLimit, int desiredDailyLimit, boolean quotaApplied, String quotaApplyError,
                         String updatedAt, String updatedBy, long configVersion, String source,
                         List<ModeAvailability> modes, Map<String, Object> knowledge, List<Change> recentChanges) { }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final AiKnowledgeProperties defaults;
    private final ObjectProvider<RagKnowledgeService> rag;
    private final ObjectProvider<WikiPublicationService> wiki;
    private final RagProperties ragProperties;
    private final int defaultDailyLimit;
    private final String scope;

    private volatile String effectiveMode;
    private volatile String desiredMode;
    private volatile String modeApplyError;
    private volatile int effectiveDailyLimit;
    private volatile int desiredDailyLimit;
    private volatile String quotaApplyError;
    private volatile long configVersion;
    private volatile String updatedAt = "";
    private volatile String updatedBy = "";
    private volatile String configSource = "ENV_DEFAULT";
    private volatile long loadedAt;
    private volatile boolean refreshing;
    private volatile boolean closed;

    public AiRuntimeConfigurationService(JdbcTemplate jdbc, PlatformTransactionManager manager,
            AiKnowledgeProperties defaults, ObjectProvider<RagKnowledgeService> rag,
            ObjectProvider<WikiPublicationService> wiki, RagProperties ragProperties,
            @Value("${chen-stack.ai.daily-limit:100}") int defaultDailyLimit,
            @Value("${chen.ai.runtime-scope:user-assistant}") String scope) {
        if (defaultDailyLimit < MIN_DAILY_LIMIT) throw new IllegalArgumentException("默认每日额度无效");
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.defaults = defaults;
        this.rag = rag;
        this.wiki = wiki;
        this.ragProperties = ragProperties;
        this.defaultDailyLimit = defaultDailyLimit;
        this.scope = scope == null || !scope.matches("[a-z0-9-]{1,32}") ? "user-assistant" : scope;
        this.effectiveMode = defaults.knowledgeMode();
        this.desiredMode = effectiveMode;
        this.effectiveDailyLimit = defaultDailyLimit;
        this.desiredDailyLimit = defaultDailyLimit;
    }

    private String modeKey() { return scope + ".knowledge-mode"; }

    @PostConstruct
    void initialize() {
        refresh(true);
    }

    @PreDestroy
    void shutdown() { closed = true; }

    /** 多实例收敛：同库不同副本在数秒内采用同一配置；失败时保留当前生效值。 */
    @Scheduled(fixedDelay = 10_000, initialDelay = 5_000)
    void poll() {
        if (closed) return;
        refresh(false);
        // 依赖恢复后重试并清除旧的未生效提示；不可用时继续保留当前生效模式。
        applyMode(desiredMode);
        applyQuota(Integer.toString(desiredDailyLimit));
    }

    @Override
    public String knowledgeMode() {
        refresh(false);
        return effectiveMode;
    }

    public int dailyLimit() {
        refresh(false);
        return effectiveDailyLimit;
    }

    public Status status() {
        refresh(true);
        return new Status(effectiveMode, desiredMode, Objects.equals(effectiveMode, desiredMode), modeApplyError,
                effectiveDailyLimit, desiredDailyLimit, effectiveDailyLimit == desiredDailyLimit, quotaApplyError,
                updatedAt, updatedBy, configVersion, configSource, availability(), knowledgeSummary(), recentChanges());
    }

    public Status setMode(String mode, int actorId) {
        String value = mode == null ? "" : mode.strip().toLowerCase(Locale.ROOT);
        if (LEGACY_CHAT.equals(value)) {
            throw new WikiProductException(400, "普通对话已不再作为可选工作模式；简单交流在三种模式下都能自然回答，请选择 RAG、Wiki 或智能混合。");
        }
        if (!MODES.contains(value)) throw new WikiProductException(400, "知识模式仅支持 rag、wiki 或 hybrid");
        ModeAvailability availability = availability(value);
        if (!availability.available()) throw new WikiProductException(409, availability.note());
        save(modeKey(), value, "小辰工作模式", actorId);
        refresh(true);
        if (!Objects.equals(effectiveMode, value)) {
            throw new WikiProductException(409, modeApplyError == null ? "模式未能立即生效，请稍后重试" : modeApplyError);
        }
        log.info("AI_RUNTIME_MODE_CHANGED mode={} actor={} version={}", value, actorId, configVersion);
        return status();
    }

    public Status setDailyLimit(int limit, int actorId) {
        if (limit < MIN_DAILY_LIMIT || limit > MAX_DAILY_LIMIT) {
            throw new WikiProductException(400, "每日额度应为 " + MIN_DAILY_LIMIT + " 至 " + MAX_DAILY_LIMIT + " 次");
        }
        save(QUOTA_KEY, Integer.toString(limit), "每日使用额度", actorId);
        refresh(true);
        if (effectiveDailyLimit != limit) {
            throw new WikiProductException(409, quotaApplyError == null ? "额度未能立即生效，请稍后重试" : quotaApplyError);
        }
        log.info("AI_RUNTIME_QUOTA_CHANGED limit={} actor={} version={}", limit, actorId, configVersion);
        return status();
    }

    private void save(String key, String value, String label, int actorId) {
        if (actorId <= 0) throw new WikiProductException(403, "需要管理员权限");
        transaction.executeWithoutResult(status -> {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT config_value,config_version FROM ai_runtime_config WHERE config_key=? FOR UPDATE", key);
            long version;
            String previous = null;
            if (rows.isEmpty()) {
                version = 1;
                jdbc.update("""
                        INSERT INTO ai_runtime_config(config_key,config_value,config_version,updated_by)
                        VALUES(?,?,?,?)
                        """, key, value, version, actorId);
            } else {
                previous = (String) rows.getFirst().get("config_value");
                version = ((Number) rows.getFirst().get("config_version")).longValue() + 1;
                if (jdbc.update("""
                        UPDATE ai_runtime_config SET config_value=?,config_version=?,updated_by=?
                        WHERE config_key=? AND config_version=?
                        """, value, version, actorId, key, version - 1) != 1) {
                    throw new IllegalStateException("AI_RUNTIME_CONFIG_CONFLICT");
                }
            }
            jdbc.update("""
                    INSERT INTO ai_runtime_config_event(config_key,old_value,new_value,actor_id,config_version)
                    VALUES(?,?,?,?,?)
                    """, key, previous, value, actorId, version);
        });
    }

    /** 读取持久配置并把可应用的值写入内存；数据库异常时保留最后一次生效值。 */
    private void refresh(boolean force) {
        if (closed) return;
        long now = System.nanoTime();
        if (!force && loadedAt != 0 && now - loadedAt < REFRESH_NANOS) return;
        synchronized (this) {
            if (refreshing) return;
            refreshing = true;
            try {
                List<Map<String, Object>> rows = jdbc.queryForList("""
                        SELECT config_key,config_value,config_version,updated_by,
                               DATE_FORMAT(updated_at,'%Y-%m-%d %H:%i:%s') updated_at
                        FROM ai_runtime_config WHERE config_key IN (?,?)
                        """, modeKey(), QUOTA_KEY);
                Map<String, Map<String, Object>> found = new LinkedHashMap<>();
                for (Map<String, Object> row : rows) found.put((String) row.get("config_key"), row);
                long highest = 0;
                String latestAt = "";
                int latestActor = 0;
                for (Map<String, Object> row : found.values()) {
                    highest = Math.max(highest, ((Number) row.get("config_version")).longValue());
                    String changedAt = String.valueOf(row.get("updated_at"));
                    if (changedAt.compareTo(latestAt) > 0) {
                        latestAt = changedAt;
                        latestActor = ((Number) row.get("updated_by")).intValue();
                    }
                }
                if (found.containsKey(modeKey())) {
                    desiredMode = String.valueOf(found.get(modeKey()).get("config_value"));
                } else {
                    desiredMode = defaults.knowledgeMode();
                }
                applyMode(desiredMode);
                if (found.containsKey(QUOTA_KEY)) {
                    applyQuota(String.valueOf(found.get(QUOTA_KEY).get("config_value")));
                } else {
                    desiredDailyLimit = defaultDailyLimit;
                    effectiveDailyLimit = defaultDailyLimit;
                    quotaApplyError = null;
                }
                configVersion = highest;
                updatedAt = latestAt;
                updatedBy = nickname(latestActor);
                configSource = found.isEmpty() ? "ENV_DEFAULT" : "DATABASE";
            } catch (RuntimeException unavailable) {
                // 不因配置表暂时不可用而中断已生效模式或额度。
                configSource = "ENV_FALLBACK";
                log.warn("AI运行配置读取失败，保留当前生效值，原因类型={}", unavailable.getClass().getSimpleName());
            } finally {
                loadedAt = System.nanoTime();
                refreshing = false;
            }
        }
    }

    private void applyMode(String value) {
        if (!MODES.contains(value) && !LEGACY_CHAT.equals(value)) {
            modeApplyError = "保存的知识模式无效，已保留当前生效模式";
            return;
        }
        ModeAvailability availability = availability(value);
        if (availability.available()) {
            effectiveMode = value;
            modeApplyError = null;
        } else {
            modeApplyError = availability.note();
        }
    }

    private void applyQuota(String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < MIN_DAILY_LIMIT || parsed > MAX_DAILY_LIMIT) throw new NumberFormatException();
            desiredDailyLimit = parsed;
            effectiveDailyLimit = parsed;
            quotaApplyError = null;
        } catch (NumberFormatException invalid) {
            quotaApplyError = "保存的每日额度无效，已保留当前生效额度";
        }
    }

    private ModeAvailability availability(String mode) {
        return switch (mode) {
            case LEGACY_CHAT -> new ModeAvailability(LEGACY_CHAT, "普通对话（历史配置）", true,
                    "历史配置仍按原样执行；简单交流在新的三种模式下同样能自然回答。");
            case "rag" -> ragAvailability();
            case "wiki" -> wikiAvailability();
            case "hybrid" -> hybridAvailability();
            default -> new ModeAvailability(mode == null ? "" : mode, "未设置", false, "知识模式无效。");
        };
    }

    private ModeAvailability ragAvailability() {
        RagKnowledgeService service = rag.getIfAvailable();
        if (service == null || !ragProperties.isEnabled()) {
            return new ModeAvailability("rag", "RAG 知识检索", false, "RAG 知识检索未启用，不能切换到此模式。");
        }
        try {
            Object state = service.diagnostics().get("ragAvailability");
            if ("CONFIGURED".equals(state)) {
                return new ModeAvailability("rag", "RAG 知识检索", true, "只依据已审校的原始知识目录回答。");
            }
        } catch (RuntimeException unavailable) {
            log.warn("RAG可用性检查失败，原因类型={}", unavailable.getClass().getSimpleName());
        }
        return new ModeAvailability("rag", "RAG 知识检索", false, "知识索引尚未完成恢复，暂不能切换到此模式。");
    }

    private ModeAvailability wikiAvailability() {
        boolean available = wiki.getIfAvailable() != null;
        return new ModeAvailability("wiki", "Wiki 知识问答", available,
                available ? "只依据当前已发布且来源有效的 Wiki 正文回答。" : "整理知识服务未启用，不能切换到此模式。");
    }

    /** 任一侧可用即可启用；只有一侧可用时明确说明降级，不假装两侧都在工作。 */
    private ModeAvailability hybridAvailability() {
        boolean ragReady = ragAvailability().available();
        boolean wikiReady = wikiAvailability().available();
        if (!ragReady && !wikiReady) {
            return new ModeAvailability("hybrid", "智能混合", false, "RAG 与 Wiki 均不可用，暂不能切换到智能混合。");
        }
        String note = ragReady && wikiReady
                ? "按问题自动选择 RAG 或 Wiki，简单交流不检索知识。"
                : ragReady ? "Wiki 当前不可用，智能混合实际只会使用 RAG。" : "RAG 当前不可用，智能混合实际只会使用 Wiki。";
        return new ModeAvailability("hybrid", "智能混合", true, note);
    }

    private List<ModeAvailability> availability() {
        List<ModeAvailability> result = new ArrayList<>();
        for (String mode : MODES) result.add(availability(mode));
        return List.copyOf(result);
    }

    private Map<String, Object> knowledgeSummary() {
        Map<String, Object> result = new LinkedHashMap<>();
        RagKnowledgeService service = rag.getIfAvailable();
        if (service != null) {
            try {
                Map<String, Object> diagnostics = service.diagnostics();
                result.put("ragReady", "CONFIGURED".equals(diagnostics.get("ragAvailability")));
                result.put("reviewedChunks", diagnostics.get("reviewedChunks"));
                result.put("lastSyncAt", diagnostics.get("lastSyncAt"));
                result.put("ragSources", service.reviewedCatalog().sourceIds().size());
            } catch (RuntimeException unavailable) {
                result.put("ragReady", false);
                result.put("ragSources", null);
            }
        } else {
            result.put("ragReady", false);
            result.put("ragSources", null);
        }
        WikiPublicationService wikiService = wiki.getIfAvailable();
        result.put("wikiReady", wikiService != null);
        Long wikiPages = null;
        if (wikiService != null) {
            try {
                wikiPages = wikiService.list("", null, 1, 1).total();
            } catch (RuntimeException unavailable) {
                result.put("wikiReady", false);
            }
        }
        result.put("wikiPages", wikiPages);
        return result;
    }

    static String auditLabel(String configKey, String modeKey) {
        return modeKey != null && modeKey.equals(configKey) ? "小辰工作模式" : "每日使用额度";
    }

    private List<Change> recentChanges() {
        try {
            return jdbc.query("""
                    SELECT config_key,old_value,new_value,actor_id,created_at FROM ai_runtime_config_event
                    WHERE config_key IN (?,?) ORDER BY id DESC LIMIT 6
                    """, (rs, index) -> new Change(auditLabel(rs.getString(1), modeKey()),
                    rs.getString(2), rs.getString(3), nickname(rs.getInt(4)),
                    TIME.format(rs.getTimestamp(5).toInstant())), modeKey(), QUOTA_KEY);
        } catch (RuntimeException unavailable) {
            return List.of();
        }
    }

    private String nickname(int actorId) {
        if (actorId <= 0) return "部署默认值";
        try {
            String name = jdbc.queryForObject("SELECT COALESCE(NULLIF(nickname,''),username) FROM sys_user WHERE id=?",
                    String.class, actorId);
            return name == null || name.isBlank() ? "管理员" : name;
        } catch (RuntimeException unavailable) {
            return "管理员";
        }
    }

    /** 仅用于审查和状态展示，不暴露数据库行或凭据。 */
    public static String describe(Instant instant) {
        return instant == null ? "" : TIME.format(instant);
    }
}
