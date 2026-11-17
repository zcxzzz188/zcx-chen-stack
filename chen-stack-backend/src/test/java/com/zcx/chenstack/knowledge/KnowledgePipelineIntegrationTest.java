package com.zcx.chenstack.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.config.RagProperties;
import com.zcx.chenstack.domain.vo.CustomerServiceEvent;
import com.zcx.chenstack.rag.NormalAiUsage;
import com.zcx.chenstack.rag.ReleaseProviderBudget;
import com.zcx.chenstack.service.CustomerServiceHistory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "G5_ISOLATED_MYSQL", matches = "jdbc:mysql://127\\.0\\.0\\.1:33401/chen_stack_g5_restore(\\?.*)?")
class KnowledgePipelineIntegrationTest {
    @TempDir
    Path temporary;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;

    @BeforeEach
    void connectToRestoreOnly() {
        var source = new DriverManagerDataSource(System.getenv("G5_ISOLATED_MYSQL"), "root", System.getenv("G5_ISOLATED_MYSQL_AUTH"));
        jdbc = new JdbcTemplate(source);
        manager = new DataSourceTransactionManager(source);
        assertEquals("chen_stack_g5_restore", jdbc.queryForObject("SELECT DATABASE()", String.class));
    }

    @Test
    void copyVerifiedLegacyVectorsWithoutEmbedding() {
        var config = new RagProperties();
        config.setCollection("chen_g5_legacy_restore");
        config.setQdrantGrpcPort(63402);
        config.setEmbeddingApiKey("isolated-unused");
        config.setEmbeddingBaseUrl(System.getenv("G5_LEGACY_EMBEDDING_BASE"));
        config.setEmbeddingModel(System.getenv("G5_LEGACY_EMBEDDING_MODEL"));
        var noProviderCalls = new ReleaseProviderBudget(null, null, 0, 0);
        KnowledgeVectors vectors = new KnowledgeVectors(config, noProviderCalls, "chen_knowledge_g5_legacy_test");
        try {
            new KnowledgeLegacyImport(jdbc, vectors, manager).run();
            assertEquals(40, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM rag_knowledge k JOIN knowledge_document_g4 d ON d.id=k.id
                    WHERE k.enabled AND k.index_status='READY'
                    """, Integer.class));
            assertEquals(43, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM rag_chunk c JOIN knowledge_document_g4 d ON d.id=c.knowledge_id
                    """, Integer.class));
        } finally {
            vectors.close();
        }
    }

    @Test
    void uploadStoresOriginalAndIndexesOnceThenAppliesWikiAndVisibility() throws Exception {
        String topic = "G5-验收-" + UUID.randomUUID().toString().substring(0, 8);
        byte[] markdown = ("# " + topic + "\r\n\r\n只用于隔离验收的原始正文。\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] text = (topic + " TXT 原件。\r\n").getBytes(StandardCharsets.UTF_8);
        var config = new RagProperties();
        config.setQdrantGrpcPort(63402);
        config.setEmbeddingBaseUrl("http://127.0.0.1:19001/v1");
        config.setEmbeddingApiKey("isolated-fixture");
        var providerUsage = new NormalAiUsage(true, temporary.resolve("usage.jsonl").toString());
        var vectors = new KnowledgeVectors(config, ReleaseProviderBudget.continuous(providerUsage, NormalAiUsage.Purpose.RETRIEVAL),
                "chen_knowledge_g5_pipeline_test");
        KnowledgeWikiGenerator generator = mock(KnowledgeWikiGenerator.class);
        when(generator.generate(anyList())).thenThrow(KnowledgeDocException.unavailable("FIXTURE_MODEL_FAILURE", "隔离故障测试"))
                .thenAnswer(invocation -> {
                    List<KnowledgeWikiGenerator.Source> sources = invocation.getArgument(0);
                    var source = sources.getFirst();
                    return new KnowledgeWikiGenerator.Draft(source.title(), source.content(), source.content());
                });
        Path uploads = temporary.resolve("uploads");
        var library = new KnowledgeLibrary(jdbc, manager, new KnowledgeFiles(uploads), vectors, generator);
        long markdownId = library.upload(topic + ".md", markdown, null);
        long textId = library.upload(topic + ".txt", text, null);
        assertEquals(markdownId, library.upload(topic + ".md", markdown, null));
        assertArrayEquals(markdown, library.download(markdownId).bytes());
        assertArrayEquals(text, library.download(textId).bytes());
        try (var stored = Files.list(uploads)) {
            assertEquals(2, stored.count());
        }
        long knowledgeId = jdbc.queryForObject("SELECT id FROM rag_knowledge WHERE document_id=?", Long.class, markdownId);
        try {
            library.processPending();
            assertEquals("READY", library.entry(knowledgeId).status());
            assertEquals(1, jdbc.queryForObject("SELECT revision FROM rag_knowledge WHERE id=?", Integer.class, knowledgeId));
            library.processWikiPending();
            assertEquals("READY", library.entry(knowledgeId).status());
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM wiki_page WHERE auto_knowledge_id=?", Integer.class, knowledgeId));
            jdbc.update("UPDATE rag_knowledge SET retry_at=NOW(6),updated_at=TIMESTAMPADD(SECOND,-30,NOW(6)) WHERE id=?", knowledgeId);
            library.processPending();
            library.processWikiPending();
            library.processWikiPending();
            assertEquals("READY", library.entry(knowledgeId).status());
            long page = jdbc.queryForObject("SELECT id FROM wiki_page WHERE auto_knowledge_id=?", Long.class, knowledgeId);
            assertEquals("PUBLISHED", jdbc.queryForObject("SELECT status FROM wiki_page WHERE id=?", String.class, page));
            assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM rag_chunk WHERE knowledge_id=?", Integer.class, knowledgeId) > 0);
            Path configFile = temporary.resolve("settings.yml");
            var settings = new KnowledgeSettings(configFile, "hybrid", "test-model", .3, 1024, 100);
            var saved = settings.save(new KnowledgeSettings.Settings("wiki", "test-model", .1, 512, 3, 75, settings.get().prompt()));
            assertEquals(saved, new KnowledgeSettings(configFile, "hybrid", "unused", .3, 1024, 100).get());
            var search = new KnowledgeSearch(jdbc, vectors, settings, mock(OpenAiChatModel.class), false);
            String indexed = library.entry(knowledgeId).content();
            assertTrue(search.retrieve(indexed, "rag").hits().stream().anyMatch(hit -> topic.equals(hit.title())));
            assertTrue(search.retrieve(topic, "wiki").hits().stream().anyMatch(hit -> topic.equals(hit.title())));
            assertEquals("wiki", search.retrieve(topic, "hybrid").engine());
            library.setEnabled(knowledgeId, false);
            assertEquals("OFFLINE", jdbc.queryForObject("SELECT status FROM wiki_page WHERE id=?", String.class, page));
            assertTrue(search.retrieve(indexed, "rag").hits().stream().noneMatch(hit -> topic.equals(hit.title())));
        } finally {
            library.remove(markdownId, true);
            library.remove(textId, true);
            library.processPending();
            library.processPending();
            jdbc.update("DELETE FROM wiki_page WHERE title LIKE ?", topic + "%");
            vectors.close();
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_document WHERE id IN (?,?)", Integer.class, markdownId, textId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM rag_chunk WHERE knowledge_id=?", Integer.class, knowledgeId));
    }

    @Test
    void conversationIsPersistedOrderedIdempotentAndScopedToOwner() {
        var history = new CustomerServiceHistory(jdbc, manager, new ObjectMapper());
        List<Integer> ids = jdbc.queryForList("SELECT id FROM sys_user ORDER BY id LIMIT 2", Integer.class);
        assertEquals(2, ids.size());
        String chat = UUID.randomUUID().toString();
        String request = UUID.randomUUID().toString();
        var calls = new AtomicInteger();
        try {
            var turn = history.record(ids.getFirst(), chat, request, "/article", "隔离测试", () -> {
                calls.incrementAndGet();
                return Flux.just(CustomerServiceEvent.text("已记录"), CustomerServiceEvent.state("DONE", null, null, null));
            });
            assertEquals(2, turn.collectList().block(Duration.ofSeconds(5)).size());
            assertEquals(2, turn.collectList().block(Duration.ofSeconds(5)).size());
            assertEquals(1, calls.get());
            assertEquals(List.of("USER", "ASSISTANT"), history.messages(ids.getFirst(), chat, Long.MAX_VALUE).messages()
                    .stream().map(CustomerServiceHistory.Message::role).toList());
            assertTrue(history.messages(ids.getLast(), chat, Long.MAX_VALUE).messages().isEmpty());
        } finally {
            jdbc.update("DELETE FROM ai_chat_session WHERE user_id=? AND chat_key=?", ids.getFirst(), chat);
        }
    }
}
