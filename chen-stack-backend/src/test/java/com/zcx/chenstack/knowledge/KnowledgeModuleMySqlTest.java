package com.zcx.chenstack.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zcx.chenstack.config.RagProperties;
import com.zcx.chenstack.controller.KnowledgeLibraryController;
import com.zcx.chenstack.rag.NormalAiUsage;
import com.zcx.chenstack.rag.ReleaseProviderBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@EnabledIfEnvironmentVariable(named = "G5_ISOLATED_MYSQL",
        matches = "jdbc:mysql://127\\.0\\.0\\.1:33401/chen_stack_g5_restore(\\?.*)?")
class KnowledgeModuleMySqlTest {
    @Test
    void replacedChapterKeepsItsIdAndLeavesUnchangedChaptersUntouched(@TempDir Path temp) throws Exception {
        String user = System.getenv("G5_ISOLATED_MYSQL_USER");
        assertNotNull(user);
        assertTrue(user.matches("g5_modules_[a-f0-9]{12}"));
        var source = new DriverManagerDataSource(System.getenv("G5_ISOLATED_MYSQL"), user,
                System.getenv("G5_ISOLATED_MYSQL_AUTH"));
        var jdbc = new JdbcTemplate(source);
        assertEquals("chen_stack_g5_restore", jdbc.queryForObject("SELECT DATABASE()", String.class));
        var provider = embeddingFixture();
        var rag = new RagProperties();
        rag.setQdrantGrpcPort(63402);
        rag.setEmbeddingBaseUrl("http://127.0.0.1:" + provider.getAddress().getPort() + "/v1");
        rag.setEmbeddingApiKey("isolated-fixture");
        var budget = ReleaseProviderBudget.continuous(new NormalAiUsage(true, temp.resolve("usage.jsonl").toString()),
                NormalAiUsage.Purpose.RETRIEVAL);
        var vectors = spy(new KnowledgeVectors(rag, budget, "chen_knowledge_g5_pipeline_test"));
        var generator = mock(KnowledgeWikiGenerator.class);
        var failOneUpdate = new AtomicBoolean(true);
        when(generator.generate(anyList())).thenAnswer(call -> {
            List<KnowledgeWikiGenerator.Source> topics = call.getArgument(0);
            var topic = topics.getFirst();
            if (topic.content().startsWith("绑定邮箱") && failOneUpdate.getAndSet(false)) {
                throw KnowledgeDocException.unavailable("FIXTURE_TIMEOUT", "隔离重试测试");
            }
            return new KnowledgeWikiGenerator.Draft(topic.title(), "验收摘要", topic.content());
        });
        when(generator.split(anyList())).thenAnswer(call -> KnowledgeWikiGenerator.applyBoundaries(call.getArgument(0),
                new KnowledgeWikiGenerator.TopicBoundary[]{new KnowledgeWikiGenerator.TopicBoundary("通知偏好", 2),
                        new KnowledgeWikiGenerator.TopicBoundary("图片审阅", 4)}));
        var library = new KnowledgeLibrary(jdbc, new DataSourceTransactionManager(source),
                new KnowledgeFiles(temp.resolve("uploads")), vectors, generator);
        var settings = mock(KnowledgeSettings.class);
        when(settings.get()).thenReturn(new KnowledgeSettings.Settings("hybrid", "module-fixture", .3, 200, 5, 100, ""));
        var search = new KnowledgeSearch(jdbc, vectors, settings, mock(OpenAiChatModel.class), false);
        var http = MockMvcBuilders.standaloneSetup(new KnowledgeLibraryController(library, null, search, settings, null)).build();
        String suffix = UUID.randomUUID().toString();
        String filename = suffix + ".md";
        String initial = "# 手册\n\n## 账号\n登录条件 " + suffix + "\n\n## 文章\n发布需要审核。\n";
        long documentId = upload(http, filename, initial);
        Long txtId = null;
        try {
            assertEquals(documentId, upload(http, filename, initial));
            List<KnowledgeLibrary.Entry> before = library.entries(0, documentId, "");
            assertEquals(2, before.size());
            var entryFilter = new KnowledgeList.Filter(1, 1, "", null, null);
            var firstPage = library.entries(entryFilter, documentId, true);
            assertEquals(2, firstPage.total());
            assertEquals(1, firstPage.items().size());
            assertNotEquals(firstPage.items().getFirst().id(), library.entries(
                    new KnowledgeList.Filter(2, 1, "", null, null), documentId, true).items().getFirst().id());
            assertEquals(0, library.entries(entryFilter, documentId, false).total());
            var uploaded = library.documents(0).stream().filter(row -> row.id() == documentId).findFirst().orElseThrow();
            assertTrue(library.documents(new KnowledgeList.Filter(1, 50, "手册",
                    uploaded.updatedAt().minusSeconds(1), uploaded.updatedAt().plusSeconds(1)), false, "PENDING")
                    .items().stream().anyMatch(row -> row.id() == documentId));
            try (var archive = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(library.downloadArchive(List.of(documentId))))) {
                assertEquals(documentId + "-" + filename, archive.getNextEntry().getName());
                assertArrayEquals(bytes(initial), archive.readAllBytes());
                assertNull(archive.getNextEntry());
            }
            long edited = id(before, "账号");
            long unchanged = id(before, "文章");
            library.processPending();
            library.processPending();
            assertTrue(library.entries(0, documentId, "").stream().allMatch(row -> row.status().equals("READY")));
            library.processWikiPending();
            library.processWikiPending();
            assertEquals(2, jdbc.queryForObject("""
                    SELECT COUNT(*) FROM wiki_page_source s JOIN rag_knowledge k ON k.id=s.knowledge_id
                    WHERE k.document_id=?
                    """, Integer.class, documentId));
            String unchangedPoint = point(jdbc, unchanged, 1);
            String originalPoint = point(jdbc, edited, 1);
            String originalQuery = "登录条件 " + suffix;
            assertTrue(search.retrieve(originalQuery, "rag").hits().stream().anyMatch(hit -> originalPoint.equals(hit.pointId())));
            String next = initial.replace("登录条件 ", "绑定邮箱和登录条件 ");
            assertEquals(documentId, library.upload(filename, bytes(next), null));
            assertEquals(documentId, library.upload(filename, bytes(next), null));
            assertEquals(List.of(edited, unchanged), library.entries(0, documentId, "").stream()
                    .map(KnowledgeLibrary.Entry::id).sorted().toList());
            assertEquals("PENDING", library.entry(edited).status());
            assertEquals("READY", library.entry(unchanged).status());
            assertEquals(1, library.entries(0, documentId, "发布需要审核").size());
            assertThrows(KnowledgeDocException.class,
                    () -> library.upload(filename, bytes("# 手册\n## 账号\n章节不能消失。"), documentId));
            assertArrayEquals(bytes(next), library.download(documentId).bytes());
            library.processPending();
            assertEquals("READY", library.entry(edited).status());
            String stagedPoint = point(jdbc, edited, 2);
            assertEquals(unchangedPoint, point(jdbc, unchanged, 1));
            String updatedPoint = point(jdbc, edited, 2);
            assertEquals(stagedPoint, updatedPoint);
            assertNotEquals(originalPoint, updatedPoint);
            assertTrue(search.retrieve(originalQuery, "rag").hits().isEmpty());
            var hybrid = search.retrieve("绑定邮箱如何操作", "hybrid");
            assertEquals("rag", hybrid.engine());
            assertTrue(hybrid.hits().stream().anyMatch(hit -> updatedPoint.equals(hit.pointId())));
            library.processWikiPending();
            assertEquals("READY", library.entry(edited).status());
            assertEquals("OFFLINE", jdbc.queryForObject("SELECT status FROM wiki_page WHERE auto_knowledge_id=?", String.class, edited));
            assertTrue(search.retrieve("绑定邮箱如何操作", "rag").hits().stream().anyMatch(hit -> updatedPoint.equals(hit.pointId())));
            jdbc.update("UPDATE rag_knowledge SET retry_at=NOW(6) WHERE id=?", edited);
            library.processWikiPending();
            assertTrue(jdbc.queryForObject("SELECT content FROM wiki_page WHERE auto_knowledge_id=?", String.class, edited)
                    .contains("绑定邮箱"));
            assertEquals("wiki", search.retrieve("绑定邮箱和登录条件 " + suffix, "hybrid").engine());
            library.processPending();
            verify(vectors).delete(List.of(originalPoint));
            verify(vectors, times(3)).prepare(anyLong(), anyLong(), anyString(), anyString(), anyList(), anyBoolean());
            verify(vectors, times(1)).prepare(eq(unchanged), anyLong(), anyString(), anyString(), anyList(), anyBoolean());

            String text = "从消息页进入通知偏好。\n\n关闭邮件不影响站内通知。\n\n上传封面后等待图片审阅。\n\n尾段说明：审阅通过才可公开展示。";
            txtId = upload(http, suffix + ".txt", text);
            assertEquals(txtId.longValue(), upload(http, suffix + ".txt", text));
            var txt = library.entries(0, txtId, "");
            assertEquals(2, txt.size());
            assertEquals("从消息页进入通知偏好。\n\n关闭邮件不影响站内通知。", library.entry(id(txt, "通知偏好")).content());
            assertEquals("上传封面后等待图片审阅。\n\n尾段说明：审阅通过才可公开展示。", library.entry(id(txt, "图片审阅")).content());
            assertArrayEquals(bytes(text), library.download(txtId).bytes());
            library.processPending();
            library.processPending();
            assertTrue(library.entries(0, txtId, "").stream().allMatch(row -> row.status().equals("READY")));
            library.processWikiPending();
            library.processWikiPending();
            verify(generator, times(1)).split(anyList());
            var ready = library.entry(edited);
            assertThrows(KnowledgeDocException.class, () -> library.setEnabled(edited, false, ready.updatedAt().minusSeconds(1)));
            var pages = new KnowledgePages(jdbc, new DataSourceTransactionManager(source), generator);
            var wiki = pages.list(new KnowledgeList.Filter(1, 50, "账号", null, null), null, null);
            assertTrue(wiki.total() > 0);
            long pageId = jdbc.queryForObject("SELECT id FROM wiki_page WHERE auto_knowledge_id=?", Long.class, edited);
            var livePage = pages.read(pageId);
            pages.publish(pageId, false, livePage.updatedAt());
            assertEquals("OFFLINE", pages.read(pageId).status());
            assertThrows(KnowledgeDocException.class, () -> pages.publish(pageId, true, livePage.updatedAt().minusSeconds(1)));
            library.setEnabled(edited, false, ready.updatedAt());
            assertFalse(library.entry(edited).enabled());
            library.setEnabled(edited, true, library.entry(edited).updatedAt());
            assertTrue(library.entry(edited).enabled());
        } finally {
            try {
                if (txtId != null) {
                    clearFixture(jdbc, vectors, txtId);
                }
                clearFixture(jdbc, vectors, documentId);
            } finally {
                vectors.close();
                provider.stop(0);
            }
        }
    }

    private static long upload(MockMvc http, String filename, String body) throws Exception {
        byte[] response = http.perform(multipart("/admin/knowledge-library/documents")
                        .file(new MockMultipartFile("file", filename, "text/plain", bytes(body))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        return new ObjectMapper().readTree(response).required("data").asLong();
    }

    private static void clearFixture(JdbcTemplate jdbc, KnowledgeVectors vectors, long id) {
        vectors.delete(jdbc.queryForList("""
                SELECT c.qdrant_point_id FROM rag_chunk c JOIN rag_knowledge k ON k.id=c.knowledge_id WHERE k.document_id=?
                """, String.class, id));
        jdbc.update("DELETE p FROM wiki_page p JOIN rag_knowledge k ON k.id=p.auto_knowledge_id WHERE k.document_id=?", id);
        jdbc.update("DELETE c FROM rag_chunk c JOIN rag_knowledge k ON k.id=c.knowledge_id WHERE k.document_id=?", id);
        jdbc.update("DELETE FROM rag_knowledge WHERE document_id=?", id);
        jdbc.update("DELETE FROM knowledge_document WHERE id=?", id);
    }

    private static HttpServer embeddingFixture() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 19001), 0);
        var json = new ObjectMapper();
        server.createContext("/v1/embeddings", exchange -> {
            try (exchange) {
                var input = json.readTree(exchange.getRequestBody()).path("input");
                var values = new ArrayList<Map<String, Object>>();
                for (var item : input.isArray() ? input : List.of(input)) {
                    String value = item.asText();
                    float[] embedding = new float[1024];
                    embedding[value.contains("绑定邮箱") ? 0 : value.contains("登录条件") ? 1 : 2] = 1;
                    values.add(Map.of("object", "embedding", "index", values.size(), "embedding", embedding));
                }
                byte[] response = json.writeValueAsBytes(Map.of("object", "list", "data", values, "model", "module-fixture",
                        "usage", Map.of("prompt_tokens", values.size(), "total_tokens", values.size())));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
        });
        server.start();
        return server;
    }

    private static String point(JdbcTemplate jdbc, long id, long revision) {
        return jdbc.queryForObject("SELECT qdrant_point_id FROM rag_chunk WHERE knowledge_id=? AND revision=?", String.class, id, revision);
    }

    private static long id(List<KnowledgeLibrary.Entry> rows, String title) {
        return rows.stream().filter(row -> row.title().equals(title)).findFirst().orElseThrow().id();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
