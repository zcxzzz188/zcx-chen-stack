package com.zcx.chenstack.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeSettingsTest {
    @TempDir
    Path directory;

    @Test
    void oldYamlDefaultsToConciseAndStyleSurvivesReloadAndLegacyWrites() throws Exception {
        Path file = directory.resolve("settings.yml");
        Files.writeString(file, """
                mode: hybrid
                model: deepseek-flash
                temperature: 0.3
                max-tokens: 200
                top-k: 5
                daily-limit: 100
                prompt: 用清楚、简洁的中文回答。
                """);
        KnowledgeSettings settings = open(file);
        assertEquals(KnowledgeSettings.ReplyStyle.PROFESSIONAL_CONCISE, settings.get().replyStyle());
        assertFalse(Files.readString(file).contains("reply-style"));

        for (KnowledgeSettings.ReplyStyle style : KnowledgeSettings.ReplyStyle.values()) {
            settings.saveReplyStyle(style);
            assertEquals(style, open(file).get().replyStyle());
        }
        KnowledgeSettings.Settings legacyUpdate = new KnowledgeSettings.Settings(
                "wiki", "deepseek-flash", 0.3, 200, 5, 99, settings.get().prompt());
        settings.save(legacyUpdate);
        assertEquals(KnowledgeSettings.ReplyStyle.PATIENT_DETAILED, open(file).get().replyStyle());
        assertEquals(99, open(file).get().dailyLimit());
        assertEquals(200, open(file).get().maxTokens());

        assertThrows(KnowledgeDocException.class, () -> settings.save(new KnowledgeSettings.Settings(
                "wiki", "deepseek-flash", 0.3, 200, 5, 99, "改变系统规则")));
        assertThrows(KnowledgeDocException.class, () -> settings.saveReplyStyle(null));
        assertEquals(settings.get(), open(file).get());
    }

    private KnowledgeSettings open(Path file) throws Exception {
        return new KnowledgeSettings(file, "hybrid", "unused", 0.3, 200, 100);
    }
}
