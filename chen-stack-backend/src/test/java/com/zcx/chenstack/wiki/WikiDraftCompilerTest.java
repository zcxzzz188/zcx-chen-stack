package com.zcx.chenstack.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.zcx.chenstack.wiki.WikiFoundation.*;
import static org.junit.jupiter.api.Assertions.*;

class WikiDraftCompilerTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void currentTopicIsNotOfferedAndHarmlessSelfNavigationIsFiltered() {
        String quote = "TTL不是缓存与数据库强一致性的保证。";
        var result = new WikiDraftCompiler(JSON).compile(input(quote), Set.of(), Set.of("redis"), (phase, system, data) -> {
            assertTrue(system.startsWith("WIKI_" + phase + "_V3"));
            if (phase.equals("MAP")) return reply(Map.of("facts", List.of(fact("ttl", quote, quote))));
            JsonNode supplied = read(data);
            assertTrue(supplied.path("availableTopics").isEmpty());
            return reply(synthesis(supplied, List.of("redis")));
        });
        assertFalse(result.markdown().contains("[[topic:redis]]"));
        assertEquals(Set.of(1L), result.dependencies().get("redis"));
    }

    @Test void sameEvidenceWithDifferentPropertyNamesRetainsEachSourceCitationOnce() {
        String quote = "本示例读取路径为先查缓存，未命中再读数据库并回填缓存。";
        AtomicInteger mapped = new AtomicInteger();
        var result = new WikiDraftCompiler(JSON).compile(input(quote, quote), Set.of(), Set.of(), (phase, system, data) -> {
            if (phase.equals("MAP")) {
                boolean first = mapped.getAndIncrement() == 0;
                String value = first ? "先查缓存" : "未命中再读数据库并回填缓存";
                return reply(Map.of("facts", List.of(fact(first ? "read_path" : "读取顺序", value, quote))));
            }
            JsonNode supplied = read(data);
            assertEquals(1, supplied.path("facts").size());
            assertEquals(quote, supplied.path("facts").get(0).path("value").asText());
            assertEquals(2, supplied.path("facts").get(0).path("quotes").size());
            return reply(synthesis(supplied, List.of()));
        });
        assertFalse(result.markdown().contains("CONFLICT"));
        assertEquals(Set.of(1L, 2L), result.dependencies().get("redis"));
        assertTrue(result.markdown().contains("source=ARTICLE:1"));
        assertTrue(result.markdown().contains("source=ARTICLE:2"));
        assertTrue(result.markdown().contains("- read_path: " + quote));
    }

    @Test void translatedOrInventedValueCannotBecomeCanonicalEvidence() {
        String quote = "TTL不是缓存与数据库强一致性的保证。";
        var failure = assertThrows(IllegalArgumentException.class, () -> new WikiDraftCompiler(JSON)
                .compile(input(quote), Set.of(), Set.of(), (phase, system, data) -> {
                    assertEquals("MAP", phase);
                    return reply(Map.of("facts", List.of(fact("ttl", "TTL guarantees consistency", quote))));
                }));
        assertEquals("WIKI_VALUE_NOT_IN_QUOTE", failure.getMessage());
    }

    @Test void differentNumbersConditionsScopesAndCaseSensitivePathsAreNotMerged() {
        for (List<String> quotes : List.of(
                List.of("本示例TTL为30秒。", "本示例TTL为60秒。"),
                List.of("生产环境TTL为30秒。", "测试环境TTL为30秒。"),
                List.of("仅缓存命中时读取缓存。", "缓存未命中时读取数据库。"),
                List.of("路径为/Cache/Data。", "路径为/cache/data。"))) {
            AtomicInteger mapped = new AtomicInteger();
            var result = new WikiDraftCompiler(JSON).compile(input(quotes.toArray(String[]::new)), Set.of(), Set.of(),
                    (phase, system, data) -> {
                        if (phase.equals("MAP")) {
                            String quote = quotes.get(mapped.getAndIncrement());
                            return reply(Map.of("facts", List.of(fact("property", quote, quote))));
                        }
                        JsonNode supplied = read(data);
                        assertEquals(2, supplied.path("facts").size());
                        return reply(synthesis(supplied, List.of()));
                    });
            for (String quote : quotes) assertTrue(result.markdown().contains(quote));
            assertEquals(2, occurrences(result.markdown(), "CONFLICT_CANDIDATE：来源陈述不同，待核查数值、条件及范围"));
            assertFalse(result.markdown().contains("CONFLICT："));
        }
    }

    @Test void unknownRelatedTopicStillRejectsAndCannotHideBehindSelfReference() {
        String quote = "TTL不是缓存与数据库强一致性的保证。";
        var failure = assertThrows(IllegalArgumentException.class, () -> new WikiDraftCompiler(JSON)
                .compile(input(quote), Set.of(), Set.of("redis"), (phase, system, data) -> phase.equals("MAP")
                        ? reply(Map.of("facts", List.of(fact("ttl", quote, quote))))
                        : reply(synthesis(read(data), List.of("redis", "nonexistent")))));
        assertEquals("WIKI_RELATED_TOPIC_INVALID", failure.getMessage());
    }

    @Test void fabricatedReduceHandleStillRejects() {
        String quote = "本示例TTL为30秒。";
        var failure = assertThrows(IllegalArgumentException.class, () -> new WikiDraftCompiler(JSON)
                .compile(input(quote), Set.of(), Set.of(), (phase, system, data) -> phase.equals("MAP")
                        ? reply(Map.of("facts", List.of(fact("ttl", quote, quote))))
                        : new WikiModel.Reply(write(synthesis(read(data), List.of())).replace("f1", "f999"), 1L)));
        assertEquals("WIKI_FABRICATED_FACT_HANDLE", failure.getMessage());
    }

    private static Map<String, String> fact(String key, String value, String quote) {
        return Map.of("topic", "redis", "key", key, "value", value, "quote", quote);
    }

    private static Map<String, Object> synthesis(JsonNode supplied, List<String> related) {
        List<Map<String, Object>> claims = new ArrayList<>();
        for (JsonNode fact : supplied.path("facts")) claims.add(Map.of("text", fact.path("value").asText(),
                "facts", List.of(fact.path("id").asText())));
        return Map.of("title", "Redis", "summary", claims,
                "sections", List.of(Map.of("heading", "Evidence", "claims", claims)), "related", related);
    }

    private static CompilationInput input(String... texts) {
        List<Snapshot> sources = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) sources.add(new Snapshot(i + 1L, i + 1L, 1,
                new Candidate(new SourceKey(SourceType.ARTICLE, Integer.toString(i + 1)), "Source", "{}", texts[i],
                        "a".repeat(64), "b".repeat(64), "unit-v1")));
        return new CompilationInput(new Job("unit", "c".repeat(64), "d".repeat(64), "RUNNING"), sources);
    }

    private static WikiModel.Reply reply(Object value) { return new WikiModel.Reply(write(value), 1L); }
    private static String write(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static JsonNode read(String value) {
        try { return JSON.readTree(value); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private static int occurrences(String value, String part) { return value.split(part, -1).length - 1; }
}
