package com.zcx.chenstack.knowledge;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Service
@ConditionalOnProperty(prefix = "chen.knowledge.simple", name = "enabled", havingValue = "true")
public class KnowledgeSettings {
    private final Path file;
    private volatile Settings current;
    private static final String DEFAULT_LEGACY_PROMPT = "用清楚、简洁的中文回答。";
    private static final Set<String> LEGACY_FIELDS = Set.of("mode", "model", "temperature", "max-tokens", "top-k", "daily-limit", "prompt");

    public enum ReplyStyle {
        PROFESSIONAL_CONCISE("使用准确清晰的中文，优先直接作答。步骤简明，保留必要的业务条件，避免冗长客套。"),
        FRIENDLY_NATURAL("使用自然亲切的中文，避免机械话术。可适度解释操作原因，保持准确，避免多余的情绪表达。"),
        PATIENT_DETAILED("使用耐心清晰的中文。复杂操作按需给出完整步骤和关键注意事项，帮助新用户理解，避免重复。");

        private final String instruction;

        ReplyStyle(String instruction) {
            this.instruction = instruction;
        }

        public String instruction() {
            return "【回复风格】仅调整表达，遵循前述身份、权限、工具和证据规则。" + instruction;
        }
    }

    public record Settings(@Pattern(regexp = "rag|wiki|hybrid") String mode,
                           @NotBlank @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}") String model,
                           @DecimalMin("0.0") @DecimalMax("2.0") double temperature,
                           @Min(64) @Max(8192) int maxTokens,
                           @Min(1) @Max(20) int topK,
                           @Min(1) @Max(10000) int dailyLimit,
                           @NotBlank @Size(max = 4000) String prompt, ReplyStyle replyStyle) {
        public Settings(String mode, String model, double temperature, int maxTokens, int topK, int dailyLimit, String prompt) {
            this(mode, model, temperature, maxTokens, topK, dailyLimit, prompt, ReplyStyle.PROFESSIONAL_CONCISE);
        }

        private Settings withReplyStyle(ReplyStyle style) {
            return new Settings(mode, model, temperature, maxTokens, topK, dailyLimit, prompt, style);
        }
    }

    public KnowledgeSettings(@Value("${chen.knowledge.simple.settings:/data/ai/settings.yml}") Path file,
                             @Value("${chen.ai.knowledge-mode:hybrid}") String defaultMode,
                             @Value("${spring.ai.openai.chat.options.model}") String model,
                             @Value("${spring.ai.openai.chat.options.temperature}") double temperature,
                             @Value("${spring.ai.openai.chat.options.max-tokens}") int maxTokens,
                             @Value("${chen-stack.ai.daily-limit:100}") int dailyLimit) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        Path directory = Files.createDirectories(this.file.getParent());
        if (!directory.equals(directory.toRealPath()) || Files.isSymbolicLink(this.file)) {
            throw new IOException("AI 配置路径包含符号链接");
        }
        if (Files.exists(this.file, LinkOption.NOFOLLOW_LINKS)) {
            current = load();
            return;
        }
        persist(new Settings("chat".equals(defaultMode) ? "hybrid" : defaultMode, model, temperature,
                Math.min(maxTokens, 8192), 5, dailyLimit, DEFAULT_LEGACY_PROMPT));
    }

    public Settings get() {
        return current;
    }

    public synchronized Settings save(Settings settings) {
        if (!current.prompt().equals(settings.prompt())) {
            throw KnowledgeDocException.bad("KNOWLEDGE_SETTINGS_INVALID", "客服提示词已由固定回复风格管理");
        }
        return persist(settings.withReplyStyle(current.replyStyle()));
    }

    public synchronized Settings saveReplyStyle(ReplyStyle style) {
        return persist(current.withReplyStyle(style));
    }

    private Settings persist(Settings settings) {
        validate(settings);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("mode", settings.mode());
        fields.put("model", settings.model());
        fields.put("temperature", settings.temperature());
        fields.put("max-tokens", settings.maxTokens());
        fields.put("top-k", settings.topK());
        fields.put("daily-limit", settings.dailyLimit());
        fields.put("prompt", settings.prompt());
        fields.put("reply-style", settings.replyStyle().name());
        try {
            if (!file.getParent().equals(file.getParent().toRealPath()) || Files.isSymbolicLink(file)) {
                throw new IOException("AI 配置路径已变更");
            }
            Path temporary = Files.createTempFile(file.getParent(), ".settings-", ".tmp");
            try {
                Files.writeString(temporary, new Yaml().dump(fields), StandardCharsets.UTF_8);
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            current = settings;
            return settings;
        } catch (IOException failure) {
            throw KnowledgeDocException.unavailable("KNOWLEDGE_SETTINGS_WRITE_FAILED", "AI 配置暂无法保存");
        }
    }

    private Settings load() throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setCodePointLimit(16384);
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            if (!(new Yaml(new SafeConstructor(options)).load(input) instanceof Map<?, ?> fields)
                    || !fields.keySet().containsAll(LEGACY_FIELDS)
                    || fields.size() != LEGACY_FIELDS.size() + (fields.containsKey("reply-style") ? 1 : 0)) {
                throw new IOException("AI 配置字段不符");
            }
            ReplyStyle style = fields.containsKey("reply-style") ? ReplyStyle.valueOf((String) fields.get("reply-style"))
                    : ReplyStyle.PROFESSIONAL_CONCISE;
            Settings settings = new Settings((String) fields.get("mode"), (String) fields.get("model"),
                    ((Number) fields.get("temperature")).doubleValue(), (Integer) fields.get("max-tokens"),
                    (Integer) fields.get("top-k"), (Integer) fields.get("daily-limit"), (String) fields.get("prompt"), style);
            validate(settings);
            return settings;
        } catch (RuntimeException failure) {
            throw new IOException("AI 配置格式或内容无效");
        }
    }

    private static void validate(Settings settings) {
        if (settings.mode() == null || !Set.of("rag", "wiki", "hybrid").contains(settings.mode())
                || settings.model() == null || !settings.model().matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}")
                || !Double.isFinite(settings.temperature()) || settings.temperature() < 0 || settings.temperature() > 2
                || settings.maxTokens() < 64 || settings.maxTokens() > 8192 || settings.topK() < 1 || settings.topK() > 20
                || settings.dailyLimit() < 1 || settings.dailyLimit() > 10000 || settings.prompt() == null
                || settings.prompt().isBlank() || settings.prompt().length() > 4000 || settings.replyStyle() == null) {
            throw KnowledgeDocException.bad("KNOWLEDGE_SETTINGS_INVALID", "请检查 AI 配置");
        }
    }
}
