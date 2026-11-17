package com.zcx.chenstack.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zcx.chenstack.config.RagConfig;
import com.zcx.chenstack.config.RagProperties;
import com.zcx.chenstack.rag.NormalAiUsage;
import com.zcx.chenstack.rag.ReleaseProviderBudget;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.qdrant.client.PointIdFactory.id;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@EnabledIfEnvironmentVariable(named = "G5_ISOLATED_MYSQL", matches = "jdbc:mysql://127\\.0\\.0\\.1:33401/chen_stack_g5_restore(\\?.*)?")
class KnowledgeRecoveryIntegrationTest {
    private enum Fault { NONE, HOLD, TIMEOUT, PROVIDER_ERROR, EMPTY, WRONG_DIMENSION, DISCONNECT }

    @TempDir
    Path temporary;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<Fault> fault = new AtomicReference<>(Fault.NONE);
    private final AtomicInteger embeddingCalls = new AtomicInteger();
    private final CountDownLatch delayedEmbeddingStarted = new CountDownLatch(1);
    private final List<Long> documents = new ArrayList<>();
    private final List<Long> manualPages = new ArrayList<>();
    private HttpServer provider;
    private ExecutorService executor;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transaction;
    private KnowledgeFiles files;
    private KnowledgeVectors vectors;
    private KnowledgeWikiGenerator wiki;
    private KnowledgeSettings settings;
    private KnowledgeLibrary library;
    private QdrantClient qdrant;
    private String collection;

    @BeforeEach
    void isolate() throws Exception {
        var data = new DriverManagerDataSource(System.getenv("G5_ISOLATED_MYSQL"), "root",
                System.getenv("G5_ISOLATED_MYSQL_AUTH"));
        jdbc = new JdbcTemplate(data);
        transaction = new DataSourceTransactionManager(data);
        assertEquals("chen_stack_g5_restore", jdbc.queryForObject("SELECT DATABASE()", String.class));
        provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        provider.setExecutor(executor);
        provider.createContext("/v1/embeddings", exchange -> {
            embeddingCalls.incrementAndGet();
            try (exchange) {
                var input = json.readTree(exchange.getRequestBody()).required("input");
                Fault failure = fault.get();
                if (failure == Fault.HOLD) {
                    delayedEmbeddingStarted.countDown();
                    new CountDownLatch(1).await();
                } else if (failure == Fault.TIMEOUT) {
                    Thread.sleep(2_000);
                } else if (failure == Fault.DISCONNECT) {
                    return;
                }
                double[] vector = new double[failure == Fault.WRONG_DIMENSION ? 8 : 1024];
                vector[0] = 1;
                var response = new ArrayList<Map<String, Object>>();
                for (int i = 0; failure != Fault.EMPTY && i < input.size(); i++) {
                    response.add(Map.of("object", "embedding", "index", i, "embedding", vector));
                }
                byte[] bytes = json.writeValueAsBytes(failure == Fault.PROVIDER_ERROR
                        ? Map.of("error", Map.of("message", "fixture unavailable"))
                        : Map.of("object", "list", "model", "text-embedding-v4", "data", response,
                                 "usage", Map.of("prompt_tokens", 1, "total_tokens", 1)));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(failure == Fault.PROVIDER_ERROR ? 503 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException disconnected) {
                if (fault.get() != Fault.TIMEOUT && fault.get() != Fault.NONE) {
                    throw disconnected;
                }
            }
        });
        provider.start();
        RagProperties config = new RagProperties();
        config.setQdrantGrpcPort(63402);
        config.setEmbeddingTimeoutSeconds(1);
        config.setEmbeddingBaseUrl("http://127.0.0.1:" + provider.getAddress().getPort() + "/v1");
        config.setEmbeddingApiKey("isolated-fixture");
        var usage = new NormalAiUsage(true, temporary.resolve("usage.jsonl").toString());
        var budget = ReleaseProviderBudget.continuous(usage, NormalAiUsage.Purpose.RETRIEVAL);
        collection = "chen_knowledge_recovery_" + UUID.randomUUID().toString().replace("-", "");
        qdrant = new QdrantClient(QdrantGrpcClient.newBuilder("127.0.0.1", 63402, false).build());
        var properties = new com.zcx.chenstack.config.KnowledgeDocProperties();
        properties.setCollection(collection);
        new KnowledgeDocIndexer(properties, config, RagConfig.embedding(config, budget), qdrant).verify(true);
        vectors = new KnowledgeVectors(config, budget, collection);
        files = new KnowledgeFiles(temporary.resolve("uploads"));
        settings = new KnowledgeSettings(temporary.resolve("settings.yml"), "hybrid", "test-model", .3, 1024, 100);
        wiki = mock(KnowledgeWikiGenerator.class);
        when(wiki.generate(anyList())).thenAnswer(invocation -> {
            List<KnowledgeWikiGenerator.Source> sources = invocation.getArgument(0);
            var first = sources.getFirst();
            return new KnowledgeWikiGenerator.Draft(first.title(), first.content(), first.content());
        });
        restartWorker();
    }

    @Test
    void processStopsWhileEmbeddingAndWikiAreInFlightThenResumesTheirDurableWork() throws Exception {
        String title = "进程恢复";
        byte[] original = ("# " + title + "\n\n必须保留的原件和合法条件。").getBytes(StandardCharsets.UTF_8);
        String fileKey = files.save(original, "md");
        long document = library.upload(title + ".md", original, null);
        documents.add(document);
        assertArrayEquals(original, files.read(fileKey, original.length));
        assertArrayEquals(original, library.download(document).bytes());
        long knowledge = jdbc.queryForObject("SELECT id FROM rag_knowledge WHERE document_id=?", Long.class, document);

        fault.set(Fault.HOLD);
        Process interruptedIndex = startWorker("index-interrupted");
        try {
            assertTrue(delayedEmbeddingStarted.await(20, TimeUnit.SECONDS));
            assertEquals("PENDING", library.entry(knowledge).status());
            assertEquals(0, revision(knowledge));
            assertEquals(0, stored(points(knowledge, 1)));
        } finally {
            stopWorker(interruptedIndex);
        }
        var staged = points(knowledge, 1);
        assertEquals(1, staged.size());
        assertEquals(1, attempts(knowledge));
        fault.set(Fault.NONE);
        due(knowledge);
        finishWorker("index-resumed");
        assertEquals("READY", library.entry(knowledge).status());
        assertEquals(staged, points(knowledge, 1));
        assertEquals(1, stored(staged));
        assertTrue(search(false).retrieve(title, "wiki").hits().isEmpty());

        Process interruptedWiki = startWorker("wiki-interrupted");
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            while (attempts(knowledge) == 0 && interruptedWiki.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(1, attempts(knowledge));
            assertEquals("READY", library.entry(knowledge).status());
        } finally {
            stopWorker(interruptedWiki);
        }
        int before = embeddingCalls.get();
        due(knowledge);
        finishWorker("wiki-resumed");
        finishWorker("wiki-confirm");
        assertEquals(before, embeddingCalls.get());
        assertEquals(1, search(false).retrieve(title, "wiki").hits().size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM wiki_page WHERE auto_knowledge_id=?", Integer.class, knowledge));
        assertArrayEquals(original, library.download(document).bytes());
    }

    private int attempts(long knowledge) {
        return jdbc.queryForObject("SELECT index_attempts FROM rag_knowledge WHERE id=?", Integer.class, knowledge);
    }

    private Process startWorker(String stage) throws IOException {
        String java = ProcessHandle.current().info().command().orElseThrow();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new ProcessBuilder(java, "-cp", classpath, WorkerProcess.class.getName(), stage, collection,
                Integer.toString(provider.getAddress().getPort()), temporary.toString())
                .redirectErrorStream(true).redirectOutput(temporary.resolve(stage + ".log").toFile()).start();
    }

    private static void stopWorker(Process process) throws InterruptedException {
        process.destroyForcibly();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS));
    }

    private void finishWorker(String stage) throws Exception {
        Process process = startWorker(stage);
        if (!process.waitFor(25, TimeUnit.SECONDS)) {
            stopWorker(process);
            fail("隔离进程未完成 " + stage);
        }
        assertEquals(0, process.exitValue(), () -> {
            try {
                return Files.readString(temporary.resolve(stage + ".log"));
            } catch (IOException failure) {
                return stage;
            }
        });
        System.out.println("隔离重启验收 stage=" + stage + " pid=" + process.pid());
    }

    public static class WorkerProcess {
        public static void main(String[] args) throws Exception {
            String db = System.getenv("G5_ISOLATED_MYSQL");
            if (db == null || !db.matches("jdbc:mysql://127\\.0\\.0\\.1:33401/chen_stack_g5_restore(\\?.*)?")) {
                throw new IllegalStateException("隔离 MySQL 地址无效");
            }
            var source = new DriverManagerDataSource(db, "root", System.getenv("G5_ISOLATED_MYSQL_AUTH"));
            var jdbc = new JdbcTemplate(source);
            var path = Path.of(args[3]);
            var config = new RagProperties();
            config.setQdrantGrpcPort(63402);
            config.setEmbeddingTimeoutSeconds(20);
            config.setEmbeddingBaseUrl("http://127.0.0.1:" + args[2] + "/v1");
            config.setEmbeddingApiKey("isolated-fixture");
            var usage = new NormalAiUsage(true, path.resolve("worker-usage.jsonl").toString());
            var vectors = new KnowledgeVectors(config, ReleaseProviderBudget.continuous(usage, NormalAiUsage.Purpose.RETRIEVAL), args[1]);
            var wiki = new KnowledgeWikiGenerator(new ObjectMapper(), null, null) {
                @Override
                public Draft generate(List<Source> sources) {
                    if ("wiki-interrupted".equals(args[0])) {
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(failure);
                        }
                    }
                    Source source = sources.getFirst();
                    return new Draft(source.title(), source.content(), source.content());
                }
            };
            try {
                var worker = new KnowledgeLibrary(jdbc, new DataSourceTransactionManager(source),
                        new KnowledgeFiles(path.resolve("uploads")), vectors, wiki);
                if (args[0].startsWith("index")) worker.processPending();
                else worker.processWikiPending();
            } finally {
                vectors.close();
            }
        }
    }

    @AfterEach
    void stopFixture() {
        if (vectors != null) vectors.close();
        if (qdrant != null) qdrant.close();
        if (provider != null) provider.stop(0);
        if (executor != null) executor.shutdownNow();
        for (long page : manualPages) {
            jdbc.update("DELETE FROM wiki_page WHERE id=?", page);
        }
        for (long document : documents) {
            jdbc.update("DELETE p FROM wiki_page p JOIN rag_knowledge k ON p.auto_knowledge_id=k.id WHERE k.document_id=?", document);
            jdbc.update("DELETE c FROM rag_chunk c JOIN rag_knowledge k ON c.knowledge_id=k.id WHERE k.document_id=?", document);
            jdbc.update("DELETE FROM rag_knowledge WHERE document_id=?", document);
            jdbc.update("DELETE FROM knowledge_document WHERE id=?", document);
        }
    }

    @Test
    void revisedWikiExposesItsCurrentVersion() {
        long knowledge = upload("页面修订", "初版正文。");
        library.processWikiPending();
        long page = jdbc.queryForObject("SELECT id FROM wiki_page WHERE auto_knowledge_id=?", Long.class, knowledge);
        var pages = new KnowledgePages(jdbc, transaction, wiki);
        assertEquals(1, pages.publicPage(page).version());
        pages.edit(page, "页面修订", "有效正文", "改版正文和限制条件。");
        pages.publish(page, true);
        assertEquals(2, pages.publicPage(page).version());
        assertEquals(2, pages.publicList("页面修订", 1, 10).items().getFirst().version());
        assertEquals("2", search(true).retrieve("页面修订", "wiki").hits().getFirst().version());
        assertEquals("1", pages.publicPage(page).citations().getFirst().version());
    }

    @Test
    void summaryCannotRouteAnAnswerAndOneRevokedSourceInvalidatesTheWholePage() {
        long first = upload("多来源主题甲", "主题甲正文和条件。");
        long second = upload("多来源主题乙", "主题乙正文和条件。");
        var pages = new KnowledgePages(jdbc, transaction, wiki);
        long page = pages.generate(List.of(first, second));
        manualPages.add(page);
        var search = search(true);
        assertTrue(search.retrieve("联合正文限定", "wiki").hits().isEmpty());
        assertThrows(KnowledgeDocException.class, () -> pages.publicPage(page));
        pages.edit(page, "多来源限制", "摘要专属词", "联合正文限定：同时保留来源甲和乙的条件。");
        pages.publish(page, true);
        assertTrue(search.retrieve("摘要专属词", "wiki").hits().isEmpty());
        var cached = search.retrieve("联合正文限定", "hybrid");
        assertEquals("wiki", cached.engine());
        assertEquals("/wiki/" + page, cached.hits().getFirst().url());
        assertEquals(2, pages.publicPage(page).citations().size());
        library.setEnabled(second, false);
        assertFalse(search.current(cached));
        assertTrue(search.retrieve("联合正文限定", "wiki").hits().isEmpty());
        assertEquals("rag", search.retrieve("联合正文限定", "hybrid").engine());
        assertThrows(KnowledgeDocException.class, () -> pages.publicPage(page));
    }

    @Test
    void embeddingFailureKeepsOldVersionAndRetryUsesTheSameStagedPoint() throws Exception {
        long knowledge = upload("Embedding恢复", "原版本仍可检索。");
        var search = search(false);
        for (Fault failure : Fault.values()) {
            if (failure == Fault.NONE || failure == Fault.HOLD) continue;
            var previous = search.retrieve("原版本", "rag");
            assertEquals(1, previous.hits().size());
            long version = revision(knowledge);
            String replacement = "保留条件并恢复向量：" + failure;
            library.save(knowledge, "Embedding恢复", replacement);
            fault.set(failure);
            library.processPending();
            assertEquals("PENDING", library.entry(knowledge).status());
            assertEquals(version, revision(knowledge));
            assertTrue(search.current(previous));
            var staged = points(knowledge, version + 1);
            assertEquals(1, staged.size());
            assertEquals(0, stored(staged));
            fault.set(Fault.NONE);
            restartWorker();
            due(knowledge);
            library.processPending();
            assertEquals("READY", library.entry(knowledge).status());
            assertEquals(staged, points(knowledge, version + 1));
            assertEquals(1, stored(staged));
            assertFalse(search.current(previous));
            assertEquals(replacement, search.retrieve("恢复向量", "rag").hits().getFirst().content());
        }
        library.save(knowledge, "Embedding恢复", "反复故障应有终态。");
        fault.set(Fault.PROVIDER_ERROR);
        for (int attempt = 0; attempt < 3; attempt++) {
            due(knowledge);
            library.processPending();
        }
        assertEquals("FAILED", library.entry(knowledge).status());
        fault.set(Fault.NONE);
        library.retry(knowledge);
        library.processPending();
        assertEquals("READY", library.entry(knowledge).status());
    }

    @Test
    void partialWriteIsInvisibleAndRestartRepairsOnlyMissingBatchThenResumesWiki() throws Exception {
        long knowledge = upload("分批写入恢复", "仍可使用的旧版本。");
        var search = search(false);
        var old = search.retrieve("旧版本", "rag");
        long version = revision(knowledge);
        var index = (KnowledgeDocIndexer) ReflectionTestUtils.getField(vectors, "index");
        var store = spy((QdrantVectorStore) ReflectionTestUtils.getField(index, "store"));
        ReflectionTestUtils.setField(index, "store", store);
        var writes = new AtomicInteger();
        doAnswer(invocation -> {
            return switch (writes.incrementAndGet()) {
                case 2 -> throw new RuntimeException(new java.util.concurrent.TimeoutException("fixture upsert timeout"));
                case 3 -> throw new IllegalStateException("fixture upsert failure");
                default -> invocation.callRealMethod();
            };
        }).when(store).add(anyList());

        library.save(knowledge, "分批写入恢复", "保留前置条件、禁止无授权发布。".repeat(260));
        library.processPending();
        var staged = points(knowledge, version + 1);
        assertTrue(staged.size() > KnowledgeChunker.EMBEDDING_BATCH && staged.size() <= 20);
        assertEquals(KnowledgeChunker.EMBEDDING_BATCH, stored(staged));
        assertEquals("PENDING", library.entry(knowledge).status());
        assertTrue(search.current(old));
        int calls = embeddingCalls.get();
        restartWorker();
        due(knowledge);
        library.processPending();
        assertEquals(calls, embeddingCalls.get());
        assertEquals("PENDING", library.entry(knowledge).status());
        assertEquals(KnowledgeChunker.EMBEDDING_BATCH, stored(staged));
        assertEquals(old.hits().getFirst().content(), search.retrieve("旧版本", "rag").hits().getFirst().content());

        calls = embeddingCalls.get();
        restartWorker();
        due(knowledge);
        library.processPending();
        assertEquals(calls + 1, embeddingCalls.get());
        assertEquals("READY", library.entry(knowledge).status());
        assertEquals(staged, points(knowledge, version + 1));
        assertEquals(staged.size(), stored(staged));
        assertFalse(search.current(old));
        assertEquals(1, stored(points(knowledge, version)));
        library.processPending();
        assertTrue(points(knowledge, version).isEmpty());

        doThrow(KnowledgeDocException.unavailable("FIXTURE_WIKI", "隔离 Wiki 失败"))
                .doReturn(new KnowledgeWikiGenerator.Draft("分批写入恢复", "保留前置条件", "保留前置条件、禁止无授权发布。"))
                .when(wiki).generate(anyList());
        library.processWikiPending();
        assertEquals("READY", library.entry(knowledge).status());
        assertTrue(search.retrieve("分批写入恢复", "wiki").hits().isEmpty());
        restartWorker();
        due(knowledge);
        library.processWikiPending();
        library.processWikiPending();
        assertEquals(1, search.retrieve("分批写入恢复", "wiki").hits().size());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM wiki_page WHERE auto_knowledge_id=?", Integer.class, knowledge));
    }

    private KnowledgeSearch search(boolean portal) {
        return new KnowledgeSearch(jdbc, vectors, settings, mock(OpenAiChatModel.class), portal);
    }

    private void restartWorker() {
        library = new KnowledgeLibrary(jdbc, transaction, files, vectors, wiki);
    }

    private long upload(String title, String content) {
        long doc = library.upload(title + ".md", ("# " + title + "\n\n" + content).getBytes(StandardCharsets.UTF_8), null);
        documents.add(doc);
        library.processPending();
        assertArrayEquals(("# " + title + "\n\n" + content).getBytes(StandardCharsets.UTF_8), library.download(doc).bytes());
        return jdbc.queryForObject("SELECT id FROM rag_knowledge WHERE document_id=?", Long.class, doc);
    }

    private void due(long knowledge) {
        jdbc.update("UPDATE rag_knowledge SET retry_at=NOW(6) WHERE id=?", knowledge);
    }

    private List<String> points(long knowledge, long revision) {
        return jdbc.queryForList("SELECT qdrant_point_id FROM rag_chunk WHERE knowledge_id=? AND revision=? ORDER BY chunk_no",
                String.class, knowledge, revision);
    }

    private long revision(long knowledge) {
        return jdbc.queryForObject("SELECT revision FROM rag_knowledge WHERE id=?", Long.class, knowledge);
    }

    private int stored(List<String> points) throws Exception {
        return qdrant.retrieveAsync(collection, points.stream().map(UUID::fromString).map(uuid -> id(uuid)).toList(),
                true, false, null).get(5, java.util.concurrent.TimeUnit.SECONDS).size();
    }
}
