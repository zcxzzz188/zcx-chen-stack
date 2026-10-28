package com.zcx.chenstack.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zcx.chenstack.domain.entity.*;
import com.zcx.chenstack.rag.KnowledgeCatalog;
import com.zcx.chenstack.rag.ReleaseProviderBudget;
import com.zcx.chenstack.security.AdminSecurity;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static com.zcx.chenstack.wiki.WikiFoundation.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit R2 driver. Preparation only uses numeric loopback; execution additionally needs an external grant. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WikiRealProviderAcceptanceIT {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectMapper DIAGNOSTIC_JSON = JSON.copy().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
            .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final boolean FINAL = Boolean.getBoolean("chen.wiki.final");
    private static final String FINAL_BATCH = System.getProperty("chen.wiki.final.batch", "");
    static { if (!FINAL_BATCH.isEmpty() && !FINAL_BATCH.matches("[0-9]{2}")) throw new IllegalArgumentException("FINAL_BATCH_INVALID"); }
    private static final boolean R2R3 = Boolean.getBoolean("chen.wiki.r2.r3");
    private static final boolean REVALIDATION = FINAL || R2R3 || Boolean.getBoolean("chen.wiki.r2.revalidation");
    private static final String RUN = FINAL ? "final" : R2R3 ? "r2r3" : REVALIDATION ? "r2r2" : "r2";
    private static final Path OUT = ROOT.resolve(FINAL ? ".work/G3_FINAL_ENGINEERING_ACCEPTANCE" + (FINAL_BATCH.isEmpty() ? "" : "/batch-" + FINAL_BATCH) : ".work/G3_P4_FINAL_ACCEPTANCE/" + (R2R3 ? "R2R3_REAL_VALIDATION" : REVALIDATION ? "R2R2_REAL_VALIDATION" : "R2_REAL_PROVIDER"));
    private static final URI ENDPOINT = URI.create("https://api.deepseek.com/chat/completions");
    private static final String MODEL = "deepseek-flash";
    private static final WikiCompilationService.Options OPTIONS = new WikiCompilationService.Options(3, 100_000, 2048, Duration.ofSeconds(60), Set.of("redis"));
    private static final WikiModel OFFLINE_IDENTITY = new WikiModel() {
        public String id() { return MODEL; }
        public boolean external() { return false; }
        public Reply generate(String system, String data, int max) { throw new AssertionError("Preparation cannot invoke a model"); }
    };
    private static final Map<String, String> FACTS = new LinkedHashMap<>();
    static {
        FACTS.put("ttl-guarantee", "TTL不是缓存与数据库强一致性的保证。");
        FACTS.put("read-path", "本示例读取路径为先查缓存，未命中再读数据库并回填缓存。");
        FACTS.put("example-ttl", "本示例将商品详情缓存的TTL设为30秒。");
        FACTS.put("expiry-jitter", "过期时间加入0到5秒随机抖动，用于分散集中失效。");
        FACTS.put("write-order", "本示例更新路径为先提交数据库，再删除对应缓存键。");
        FACTS.put("delete-compensation", "数据库提交和缓存删除不构成跨组件原子事务；删除失败需要记录并补偿。");
        FACTS.put("refill-race", "并发回填可能写回旧值，因此应单独验证该竞态。");
    }
    private final String rehearsalRun = UUID.randomUUID().toString().substring(0, 8);
    private final List<Map<String, Object>> checks = new ArrayList<>();
    private JsonNode state;
    private JdbcTemplate jdbc;
    private WikiFoundation foundation;
    private com.zcx.chenstack.rag.CatalogLifecycle catalog;
    private List<Long> snapshots;
    private Job paidJob;

    @BeforeAll void openOwnedMysql() throws Exception {
        assertEquals("true", System.getProperty("chen.wiki.r2.it"), "Explicit isolated R2 opt-in required");
        String mode = System.getProperty("chen.wiki.r2.mode", "prepare");
        assertTrue(Set.of("prepare", "prepare-fresh", "execute").contains(mode) || FINAL && Set.of("replay", "verify").contains(mode));
        assertTrue(REVALIDATION || !"prepare-fresh".equals(mode));
        Files.createDirectories(OUT.resolve("runtime"));
        Path privateState = OUT.resolve("runtime/mysql-private.json");
        boolean fresh = !Files.exists(privateState);
        if (fresh) {
            assertNotEquals("execute", mode);
            String owner = UUID.randomUUID().toString().replace("-", "");
            state = JSON.valueToTree(Map.of("owner", owner, "resource", "chen-wiki-" + RUN + "-" + owner.substring(0, 12),
                    "password", UUID.randomUUID().toString(), "requestId", "g3-p4-" + RUN + "-real-" + owner.substring(0, 12),
                    "budgetId", "wiki-" + RUN + "-" + owner.substring(0, 12)));
            Files.writeString(privateState, JSON.writeValueAsString(state), StandardOpenOption.CREATE_NEW);
            docker("image", "inspect", "mysql:8.0");
            docker("network", "create", "--driver", "bridge", "--label", "chen.wiki.r2=" + owner, resource());
            docker("volume", "create", "--label", "chen.wiki.r2=" + owner, resource());
            Path env = OUT.resolve("runtime/mysql.env");
            Files.writeString(env, "MYSQL_ROOT_PASSWORD=" + state.path("password").asText() + "\nMYSQL_ROOT_HOST=%\nMYSQL_DATABASE=wiki_r2_acceptance\n", StandardOpenOption.CREATE_NEW);
            docker("run", "--pull=never", "--detach", "--name", resource(), "--label", "chen.wiki.r2=" + owner,
                    "--network", resource(), "--dns", "127.0.0.1", "--publish", "127.0.0.1::3306", "--restart=no",
                    "--volume", resource() + ":/var/lib/mysql", "--env-file", env.toString(), "mysql:8.0");
        } else {
            state = JSON.readTree(Files.readString(privateState)); verifyOwner();
            if (!"true".equals(docker("inspect", "--format", "{{.State.Running}}", resource()).strip())) docker("start", resource());
        }
        verifyOwner();
        String address = docker("port", resource(), "3306/tcp").strip();
        assertTrue(address.matches("127\\.0\\.0\\.1:[0-9]+"));
        String url = "jdbc:mysql://" + address + "/wiki_r2_acceptance?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=1000&socketTimeout=5000";
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (true) {
            try (var ignored = DriverManager.getConnection(url, "root", state.path("password").asText())) { break; }
            catch (java.sql.SQLException notReady) { if (System.nanoTime() > deadline) throw new IOException("R2_MYSQL_START_TIMEOUT"); Thread.sleep(250); }
        }
        var dataSource = new DriverManagerDataSource(url, "root", state.path("password").asText());
        jdbc = new JdbcTemplate(dataSource); jdbc.setQueryTimeout(5);
        catalog = new KnowledgeCatalog(List.of()).lifecycle();
        foundation = new WikiFoundation(jdbc, new DataSourceTransactionManager(dataSource), new WikiSourceReader(jdbc, catalog, JSON), new AdminSecurity());
        login();
        int tables = Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()", Integer.class));
        if (tables == 0) {
            for (String ddl : List.of("sql/g3_wiki_foundation.sql", "sql/g3_wiki_compiler.sql")) {
                String sql = Files.readString(ROOT.resolve(ddl)).replaceAll("(?m)^--[^\\r\\n]*", "");
                for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
            }
            jdbc.execute("CREATE TABLE article(id INT PRIMARY KEY,user_id INT,title VARCHAR(100),description VARCHAR(200),content TEXT,tag VARCHAR(100),reprint_type TINYINT,reprint_url VARCHAR(100),edit_status TINYINT,examine_status TINYINT,visible_range TINYINT,is_deleted TINYINT) ENGINE=InnoDB");
            String a = fixture("r2-redis-expiry.md"), b = fixture("r2-redis-consistency.md");
            add(1, "Redis缓存过期策略（合成）", a); add(2, "Redis缓存失效与一致性（合成）", b);
            add(3, "超限合成资料", a + "过期策略仅供测试。".repeat(900));
            add(4, "取消用合成资料", a); add(5, "撤权用合成资料", a);
        }
    }

    @AfterAll void retainExactDatabaseStopped() throws Exception {
        SecurityContextHolder.clearContext(); if (catalog != null) catalog.close();
        if (state != null) { verifyOwner(); docker("stop", "--time", "10", resource()); }
    }

    @Test void acceptance() throws Exception {
        if (FINAL && "replay".equals(System.getProperty("chen.wiki.r2.mode"))) { replayRealResponses(); return; }
        if (FINAL && "verify".equals(System.getProperty("chen.wiki.r2.mode"))) { verifyRealDraftAfterRestart(); return; }
        if ("execute".equals(System.getProperty("chen.wiki.r2.mode", "prepare"))) { executeApproved(); return; }
        snapshots = authorize(1, 2);
        paidJob = service(OFFLINE_IDENTITY).queue(state.path("requestId").asText(), snapshots, OPTIONS);
        assertEquals("QUEUED", paidJob.state());
        Path ledger = paidLedger(); Files.createDirectories(ledger.getParent());
        if (!Files.exists(ledger)) Files.writeString(ledger, "CHEN_RELEASE_HTTP_BUDGET_V1|" + budgetId() + "|0|3\n", StandardOpenOption.CREATE_NEW);
        assertEquals(0L, paidBudget().snapshot().get("chat"));
        assertFalse(Files.exists(ledger.resolveSibling(ledger.getFileName() + ".halt")));
        assertFalse(Files.exists(ledger.resolveSibling("authorization.json")), "A cannot create or replace a real authorization");
        assertThrows(SecurityException.class, this::executeApproved);
        List<Map<String, Object>> sourceEvidence = new ArrayList<>();
        for (Snapshot snapshot : foundation.inputs(paidJob.requestId()).sources()) {
            List<Map<String, Object>> quotes = new ArrayList<>();
            FACTS.forEach((key, quote) -> { int start = snapshot.material().text().indexOf(quote);
                if (start >= 0) quotes.add(Map.of("key", key, "quote", quote, "utf16Start", start, "utf16End", start + quote.length())); });
            sourceEvidence.add(Map.of("snapshotId", snapshot.id(), "source", snapshot.material().key(), "fingerprint", snapshot.material().fingerprint(), "quotes", quotes));
        }
        record("P1_INSPECT_AUTHORIZE_PREPARE", Map.of("snapshotIds", snapshots, "job", paidJob));
        rehearse();
        if (!"prepare-fresh".equals(System.getProperty("chen.wiki.r2.mode"))) {
            budgetDenied(); limitDenied(); unknownStops(); boundaryStops(false); boundaryStops(true);
        }
        assertEquals("QUEUED", foundation.status(paidJob.requestId()).state());
        assertEquals(0, countAttempts(paidJob.requestId())); assertEquals(0L, paidBudget().snapshot().get("chat"));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("approved", REVALIDATION); plan.put("model", MODEL); plan.put("endpoint", ENDPOINT.toString());
        plan.put("requestId", paidJob.requestId()); plan.put("manifestSha256", paidJob.manifestHash()); plan.put("compilerContractHash", paidJob.contractHash());
        plan.put("snapshotIds", snapshots); plan.put("options", OPTIONS.toString()); plan.put("expectedHttpCalls", 3); plan.put("maxOutputTokens", 2048);
        plan.put("proposedMaxUsd", "0.05"); plan.put("conditionalReservedUsd", WikiProviderAuthorization.reservedUsd(3).toPlainString());
        if (FINAL) plan.put("authorizationBasis", "USER_G3_CONTINUOUS_AUTHORIZATION_2026_10_27; bounded batch=3; maxUsd is a local estimate, not a proven billing cap");
        plan.put("proposedExpiresAt", Instant.now().plus(Duration.ofHours(24)).toString()); plan.put("sources", sourceEvidence);
        plan.put("budgetId", budgetId()); plan.put("ledgerPath", ledger.toString()); plan.put("ledger", paidBudget().snapshot());
        Path planPath = OUT.resolve("prepared-plan.json");
        if (Files.exists(planPath)) {
            JsonNode previous = JSON.readTree(Files.readString(planPath));
            assertEquals(paidJob.manifestHash(), previous.path("manifestSha256").asText());
            plan.put("proposedExpiresAt", previous.path("proposedExpiresAt").asText());
        }
        write(planPath, plan); write(OUT.resolve("preparation-evidence.json"), checks);
        record("PAID_SCOPE_ZERO_SENDS", Map.of("state", "QUEUED", "attempts", 0, "chat", 0, "embedding", 0));
        write(OUT.resolve("preparation-evidence.json"), checks);
        System.out.println((REVALIDATION ? "G3_P4_" + RUN.toUpperCase(Locale.ROOT) + "=PREPARED_AUTHORIZED" : "G3_P4_R2=READY_FOR_PAID_AUTHORIZATION")
                + ";REAL_CHAT_CALLS=0;REAL_EMBEDDING_CALLS=0;PREDICTED_HTTP=3");
    }

    private void replayRealResponses() throws Exception {
        List<WikiModel.Reply> originals = new ArrayList<>();
        boolean versionTwo = "v2".equals(System.getProperty("chen.wiki.final.replay"));
        String originalBytes = fixture(versionTwo ? "final-v2-real-responses.jsonl" : "r2r3-real-responses.jsonl");
        assertEquals(versionTwo ? "ee3ffc7cb7d2986c4d9ed82c9bbd451fe83935211e8af23d5f64d89fe3a8dc23"
                : "1ad64d407c441eb662ab47d3b6e158b46748b5d7ff37e6f061a2617120af5d62", KnowledgeCatalog.hash(originalBytes));
        for (String line : originalBytes.lines().filter(s -> !s.isBlank()).toList()) originals.add(JSON.readValue(line, WikiModel.Reply.class));
        assertEquals(3, originals.size());
        List<JsonNode> legacyFacts = new ArrayList<>();
        Map<String, String> handles = new LinkedHashMap<>();
        var cursor = new java.util.concurrent.atomic.AtomicInteger();
        WikiModel replay = new WikiModel() {
            public String id() { return versionTwo ? "final-offline-replay-v2-to-v3" : "r2r3-offline-replay-v1-to-v3"; }
            public boolean external() { return false; }
            public Reply generate(String system, String data, int max) {
                int call = cursor.getAndIncrement();
                assertTrue(call < originals.size(), "Replay cannot issue additional calls");
                Reply original = originals.get(call);
                JsonNode response;
                try { response = JSON.readTree(original.json()); }
                catch (IOException failure) { throw new IllegalStateException(failure); }
                if (call < 2) {
                    response.path("facts").forEach(fact -> {
                        // Reconstruct the v2 handle allocation before translating it to v3.
                        if (!versionTwo || legacyFacts.stream().noneMatch(prior ->
                                WikiDraftCompiler.topic(prior.path("topic").asText()).equals(WikiDraftCompiler.topic(fact.path("topic").asText()))
                                && WikiDraftCompiler.topic(prior.path("key").asText()).equals(WikiDraftCompiler.topic(fact.path("key").asText()))
                                && prior.path("quote").asText().equals(fact.path("quote").asText()))) legacyFacts.add(fact.deepCopy());
                        // v1 allowed translated values; v2 uses the complete, unchanged source quote.
                        ((com.fasterxml.jackson.databind.node.ObjectNode) fact).put("value", fact.path("quote").asText());
                    });
                } else {
                    try {
                        assertEquals(versionTwo ? 10 : 11, legacyFacts.size());
                        JsonNode currentData = JSON.readTree(data);
                        JsonNode current = currentData.path("facts");
                        for (int index = 0; index < legacyFacts.size(); index++) {
                            JsonNode old = legacyFacts.get(index);
                            assertEquals(old.path("topic").asText(), currentData.path("topic").asText());
                            List<String> matches = new ArrayList<>();
                            for (JsonNode fact : current) {
                                if (fact.path("value").asText().equals(old.path("quote").asText())) matches.add(fact.path("id").asText());
                            }
                            assertEquals(1, matches.size(), "Each legacy handle must map to exactly one unchanged source quote");
                            handles.put("f" + (index + 1), matches.getFirst());
                        }
                        remapClaims(response.path("summary"), handles);
                        for (JsonNode section : response.path("sections")) remapClaims(section.path("claims"), handles);
                    } catch (IOException failure) { throw new IllegalStateException(failure); }
                }
                return new Reply(response.toString(), original.usageTokens(), original.inputTokens(), original.outputTokens());
            }
        };
        List<Long> ids = authorize(1, 2);
        String request = "g3-final-replay-" + rehearsalRun;
        Draft draft = service(replay).compile(request, ids, OPTIONS);
        assertEquals(3, cursor.get());
        assertEquals("DRAFT_READY", foundation.status(request).state());
        assertEquals(draft, foundation.readDraft(request));
        String evidence = draft.markdown().substring(draft.markdown().lastIndexOf("### 关键事实与证据"));
        assertEquals(9, occurrences(evidence, " [SEMANTIC_NOT_VERIFIED] [f"));
        assertEquals(11, occurrences(draft.markdown(), "  - snapshot="));
        assertFalse(draft.markdown().contains("[CONFLICT]"));
        for (String quote : FACTS.values()) assertTrue(draft.markdown().contains(quote));
        Files.writeString(OUT.resolve("replay-draft.md"), draft.markdown());
        write(OUT.resolve("replay-evidence.json"), Map.of("adapterVersion", versionTwo ? "final-v2-to-v3-source-quote-handles-1" : "r2r3-v1-to-v3-source-quote-handles-1", "originalResponsesUnchanged", true,
                "requestId", request, "state", "DRAFT_READY", "actualProviderCalls", 0, "recordedHistoricalTokens", originals.stream().mapToLong(WikiModel.Reply::usageTokens).sum(),
                "handleMapping", handles, "uniqueFacts", 9, "citations", 11, "bodyHash", draft.bodyHash()));
    }

    private static void remapClaims(JsonNode claims, Map<String, String> handles) {
        for (JsonNode claim : claims) {
            var mapped = JSON.createArrayNode();
            var unique = new LinkedHashSet<String>();
            for (JsonNode handle : claim.path("facts")) unique.add(Objects.requireNonNull(handles.get(handle.asText()), "Unknown legacy fact handle"));
            unique.forEach(mapped::add);
            ((com.fasterxml.jackson.databind.node.ObjectNode) claim).set("facts", mapped);
        }
    }

    private void verifyRealDraftAfterRestart() throws Exception {
        String request = state.path("requestId").asText();
        assertEquals("DRAFT_READY", foundation.status(request).state());
        Draft draft = foundation.readDraft(request);
        assertEquals(Files.readString(OUT.resolve("real-draft.md")), draft.markdown());
        assertEquals(3, countAttempts(request));
        assertEquals(3L, paidBudget().snapshot().get("chat"));
        write(OUT.resolve("restart-evidence.json"), Map.of("newJvmAndMysqlRestart", true, "requestId", request, "state", "DRAFT_READY",
                "bodyHash", draft.bodyHash(), "attemptsUnchanged", 3, "newProviderCalls", 0));
    }

    private void rehearse() throws Exception {
        try (var http = new MockProvider(false)) {
            Scope scope = scope("success", snapshots, OPTIONS, http.endpoint(), 3);
            try (var model = model(http, scope)) {
                Draft draft = service(REVALIDATION ? capture(model, "rehearsal", scope.budget()) : model).compile(scope.job().requestId(), snapshots, OPTIONS);
                write(OUT.resolve("rehearsal-http.json"), http.requests); Files.writeString(OUT.resolve("rehearsal-draft.md"), draft.markdown());
                write(OUT.resolve("rehearsal-history.json"), jdbc.queryForList("SELECT request_id,markdown,body_hash FROM wiki_draft ORDER BY request_id"));
                assertEquals(draft, foundation.readDraft(scope.job().requestId())); assertEquals(3, http.requests.size());
                assertEquals(3L, scope.budget().snapshot().get("chat"));
                assertEquals(List.of("MAP", "MAP", "REDUCE"), jdbc.queryForList("SELECT phase FROM wiki_compile_attempt WHERE request_id=? ORDER BY call_no", String.class, scope.job().requestId()));
                String evidence = draft.markdown().substring(draft.markdown().lastIndexOf("### 关键事实与证据"));
                assertEquals(7, occurrences(evidence, " [SEMANTIC_NOT_VERIFIED] [f"));
                assertEquals(9, occurrences(draft.markdown(), "  - snapshot="));
                for (String quote : FACTS.values()) assertTrue(draft.markdown().contains(quote));
                record("LOOPBACK_COMPILE_AND_REREAD", Map.of("requestId", scope.job().requestId(), "http", 3, "phases", List.of("MAP", "MAP", "REDUCE"), "uniqueFacts", 7, "quoteOccurrences", 9, "bodyHash", draft.bodyHash()));
                assertThrows(SecurityException.class, () -> scope.authorization().require(scope.job().requestId(), "0".repeat(64)));
                Scope expired = scope("expired", snapshots, OPTIONS, http.endpoint(), 3, Instant.now().minusSeconds(1));
                assertThrows(SecurityException.class, () -> expired.authorization().require(expired.job().requestId(), expired.job().manifestHash()));
            }
        }
    }

    private void budgetDenied() throws Exception {
        try (var http = new MockProvider(false)) {
            Scope scope = scope("budget", snapshots, OPTIONS, http.endpoint(), 3);
            for (int i = 0; i < 3; i++) scope.budget().reserve(ReleaseProviderBudget.Kind.CHAT);
            try (var model = model(http, scope)) { assertEquals("WIKI_PROVIDER_BUDGET_EXHAUSTED", assertThrows(SecurityException.class, () -> service(model).compile(scope.job().requestId(), snapshots, OPTIONS)).getMessage()); }
            assertEquals(0, http.requests.size()); assertEquals(3L, scope.budget().snapshot().get("chat"));
            record("BUDGET_EXHAUSTED_ZERO_HTTP", Map.of("http", 0, "reserved", 3));
        }
    }

    private void limitDenied() throws Exception {
        List<Long> oversized = authorize(3);
        try (var http = new MockProvider(false)) {
            Scope scope = scope("wire-limit", oversized, OPTIONS, http.endpoint(), 3);
            try (var model = model(http, scope)) { assertEquals("WIKI_WIRE_LIMIT", assertThrows(SecurityException.class, () -> service(model).compile(scope.job().requestId(), oversized, OPTIONS)).getMessage()); }
            assertEquals(0, http.requests.size()); assertEquals(0L, scope.budget().snapshot().get("chat"));
            var outputLimit = new WikiCompilationService.Options(3, 100_000, 2049, Duration.ofSeconds(60), Set.of("redis"));
            Scope output = scope("output-limit", snapshots, outputLimit, http.endpoint(), 3);
            try (var model = model(http, output)) { assertEquals("WIKI_OUTPUT_LIMIT_INVALID", assertThrows(IllegalStateException.class, () -> service(model).compile(output.job().requestId(), snapshots, outputLimit)).getMessage()); }
            assertEquals(0, http.requests.size()); assertEquals(0L, output.budget().snapshot().get("chat"));
            record("WIRE_AND_OUTPUT_LIMITS_ZERO_HTTP", Map.of("http", 0, "reserved", 0));
        }
    }

    private void unknownStops() throws Exception {
        try (var http = new MockProvider(true)) {
            Scope scope = scope("unknown", snapshots, OPTIONS, http.endpoint(), 3);
            try (var model = model(http, scope)) { assertEquals("WIKI_USAGE_UNKNOWN", assertThrows(IllegalStateException.class, () -> service(model).compile(scope.job().requestId(), snapshots, OPTIONS)).getMessage()); }
            assertEquals("UNKNOWN", jdbc.queryForObject("SELECT state FROM wiki_compile_attempt WHERE request_id=?", String.class, scope.job().requestId()));
            try (var restarted = model(http, scope)) { assertThrows(SecurityException.class, () -> service(restarted).compile(scope.job().requestId(), snapshots, OPTIONS)); }
            assertEquals(1, http.requests.size()); assertEquals(1L, scope.budget().snapshot().get("chat"));
            record("UNKNOWN_PERSISTENT_HALT_NO_REPLAY", Map.of("http", 1, "reserved", 1, "state", "UNKNOWN"));
        }
    }

    private void boundaryStops(boolean retract) throws Exception {
        List<Long> ids = authorize(retract ? 5 : 4);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var http = new MockProvider(false); var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Scope scope = scope(retract ? "revoke" : "cancel", ids, OPTIONS, http.endpoint(), 3);
            try (var actual = model(http, scope)) {
                WikiModel delayed = new WikiModel() {
                    public String id() { return actual.id(); }
                    public void authorize(String id, String hash) { actual.authorize(id, hash); }
                    public void outcomeUnknown() { actual.outcomeUnknown(); }
                    public Reply generate(String system, String data, int max) { throw new AssertionError("SendContext required"); }
                    public Reply generate(String system, String data, int max, SendContext context) {
                        var gate = new SendContext(context.requestId(), context.manifestHash(), () -> {
                            entered.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
                            context.beforeSend();
                        }); context.onCancel(gate::cancel); return actual.generate(system, data, max, gate);
                    }
                };
                WikiCompilationService service = service(delayed);
                Future<?> result = threads.submit(() -> { login(); try { return service.compile(scope.job().requestId(), ids, OPTIONS); } finally { SecurityContextHolder.clearContext(); } });
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                if (retract) jdbc.update("UPDATE article SET visible_range=1 WHERE id=5"); else service.cancel(scope.job().requestId());
                assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS)); release.countDown();
                assertEquals(retract ? "INVALIDATED" : "CANCELLED", foundation.status(scope.job().requestId()).state());
                assertEquals("NOT_SENT", jdbc.queryForObject("SELECT state FROM wiki_compile_attempt WHERE request_id=?", String.class, scope.job().requestId()));
                assertEquals(0, http.requests.size()); record(retract ? "SOURCE_REVOKED_ZERO_HTTP" : "CANCELLED_ZERO_HTTP", Map.of("http", 0, "reserved", 1));
            } finally { release.countDown(); }
        }
    }

    private void executeApproved() throws Exception {
        String hash = System.getenv("CHEN_WIKI_R2_AUTHORIZATION_SHA256");
        Path grantPath = paidLedger().resolveSibling("authorization.json");
        if (hash == null || !hash.matches("[a-fA-F0-9]{64}") || !Files.isRegularFile(grantPath)) throw new SecurityException("R2_PAID_AUTHORIZATION_REQUIRED");
        JsonNode plan = JSON.readTree(Files.readString(OUT.resolve("prepared-plan.json")));
        String request = plan.path("requestId").asText(), manifest = plan.path("manifestSha256").asText();
        WikiProviderAuthorization authorization = new WikiProviderAuthorization(grantPath.toString(), hash, paidLedger().toString(), budgetId(), "", ENDPOINT, MODEL);
        WikiProviderAuthorization.Grant grant = authorization.require(request, manifest);
        if (grant.maxChatCalls() != 3 || grant.maxUsd().compareTo(new BigDecimal("0.05")) != 0 || grant.manifests().size() != 1
                || Instant.parse(grant.expiresAt()).isAfter(Instant.parse(plan.path("proposedExpiresAt").asText()))) throw new SecurityException("R2_APPROVAL_SCOPE_MISMATCH");
        assertEquals("QUEUED", foundation.status(request).state()); assertEquals(0, countAttempts(request)); assertEquals(0L, paidBudget().snapshot().get("chat"));
        assertEquals(manifest, foundation.inputs(request).job().manifestHash());
        List<Long> ids = new ArrayList<>(); plan.path("snapshotIds").forEach(n -> ids.add(n.longValue()));
        String key = System.getenv("SPRING_AI_OPENAI_API_KEY");
        if (key == null || key.isBlank()) throw new SecurityException("R2_EXISTING_PROVIDER_KEY_REQUIRED");
        try (var actual = SpringAiWikiModel.deepseek(ENDPOINT, MODEL, key, authorization)) {
            WikiModel capture = capture(actual, "real", paidBudget());
            try {
                Draft draft = service(capture).compile(request, ids, OPTIONS);
                assertEquals(draft, foundation.readDraft(request)); Files.writeString(OUT.resolve("real-draft.md"), draft.markdown(), StandardOpenOption.CREATE_NEW);
            } finally {
                write(OUT.resolve("real-attempts.json"), jdbc.queryForList("SELECT call_no,phase,state,usage_tokens FROM wiki_compile_attempt WHERE request_id=? ORDER BY call_no", request));
                write(OUT.resolve("real-budget.json"), paidBudget().snapshot());
                write(OUT.resolve("real-job.json"), foundation.status(request));
                write(OUT.resolve("real-sql-budget.json"), jdbc.queryForMap("SELECT model,max_calls,max_units,output_tokens,first_stop FROM wiki_compile_budget WHERE request_id=?", request));
            }
        }
    }

    private WikiModel capture(SpringAiWikiModel actual, String prefix, ReleaseProviderBudget budget) {
        return new WikiModel() {
                public String id() { return actual.id(); }
                public void authorize(String id, String value) { actual.authorize(id, value); }
                public void outcomeUnknown() { actual.outcomeUnknown(); }
                public Reply generate(String system, String data, int max) { throw new AssertionError("SendContext required"); }
                public Reply generate(String system, String data, int max, SendContext context) {
                    long start = System.nanoTime(); Reply reply = null; RuntimeException original = null;
                    try { reply = actual.generate(system, data, max, context); saveReply(prefix, reply); return reply; }
                    catch (RuntimeException failure) {
                        original = failure;
                        if (failure instanceof KnownUsageFailure known) { reply = known.reply(); saveReply(prefix, reply); }
                        throw failure;
                    } finally {
                        try { saveObservation(prefix, context, reply, budget, start); }
                        catch (RuntimeException evidence) {
                            if (original == null) throw evidence;
                            original.addSuppressed(new IllegalStateException("R2_DIAGNOSTIC_WRITE_FAILED"));
                        }
                    }
                }
            };
    }

    private void saveReply(String prefix, WikiModel.Reply reply) {
        try { Files.writeString(OUT.resolve(prefix + "-responses.jsonl"), JSON.writeValueAsString(reply) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException failed) { throw new IllegalStateException("R2_RESPONSE_EVIDENCE_WRITE_FAILED"); }
    }

    private void saveObservation(String prefix, WikiModel.SendContext context, WikiModel.Reply reply, ReleaseProviderBudget budget, long start) {
        Map<String, Object> record = new LinkedHashMap<>(jdbc.queryForMap("SELECT call_no AS attempt,phase FROM wiki_compile_attempt WHERE request_id=? ORDER BY call_no DESC LIMIT 1", context.requestId()));
        record.put("requestId", context.requestId()); record.put("diagnostics", context.diagnostics().current());
        record.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)); record.put("ledger", budget.snapshot());
        record.put("inputTokens", reply == null ? null : reply.inputTokens()); record.put("outputTokens", reply == null ? null : reply.outputTokens());
        record.put("usageTokens", reply == null ? null : reply.usageTokens());
        try { Files.writeString(OUT.resolve(prefix + "-observations.jsonl"), DIAGNOSTIC_JSON.writeValueAsString(record) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException failed) { throw new IllegalStateException("R2_DIAGNOSTIC_WRITE_FAILED"); }
    }
    private record Scope(Job job, WikiProviderAuthorization authorization, ReleaseProviderBudget budget) { }
    private Scope scope(String suffix, List<Long> ids, WikiCompilationService.Options options, URI endpoint, int calls) throws Exception {
        return scope(suffix, ids, options, endpoint, calls, Instant.now().plusSeconds(3600));
    }
    private Scope scope(String suffix, List<Long> ids, WikiCompilationService.Options options, URI endpoint, int calls, Instant expiry) throws Exception {
        Job job = service(OFFLINE_IDENTITY).queue("r2-" + rehearsalRun + "-" + suffix, ids, options);
        Path dir = OUT.resolve("runtime/mock/" + rehearsalRun + "/" + suffix); Files.createDirectories(dir);
        String budget = "wiki-r2-mock-" + rehearsalRun + "-" + suffix;
        Path ledger = dir.resolve("attempts.ledger"), grantPath = dir.resolve("authorization.json");
        Files.writeString(ledger, "CHEN_RELEASE_HTTP_BUDGET_V1|" + budget + "|0|" + calls + "\n", StandardOpenOption.CREATE_NEW);
        var grant = new WikiProviderAuthorization.Grant(1, budget, ledger.toString(), endpoint.toString(), MODEL, expiry.toString(), calls, new BigDecimal("0.05"), Map.of(job.requestId(), job.manifestHash()));
        String body = JSON.writeValueAsString(grant); Files.writeString(grantPath, body, StandardOpenOption.CREATE_NEW);
        return new Scope(job, new WikiProviderAuthorization(grantPath.toString(), KnowledgeCatalog.hash(body), ledger.toString(), budget, "", endpoint, MODEL), new ReleaseProviderBudget(ledger.toString(), budget, 0, calls));
    }
    private SpringAiWikiModel model(MockProvider http, Scope scope) { return SpringAiWikiModel.deepseek(http.endpoint(), MODEL, "synthetic-loopback-only", scope.authorization()); }
    private WikiCompilationService service(WikiModel model) { return new WikiCompilationService(foundation, jdbc, JSON, model); }
    private List<Long> authorize(int... ids) { List<Long> result = new ArrayList<>(); for (int id : ids) { SourceKey key = new SourceKey(SourceType.ARTICLE, Integer.toString(id)); Candidate candidate = foundation.inspect(key); result.add(foundation.authorize(key, candidate.fingerprint()).id()); } return result; }
    private void add(int id, String title, String text) { jdbc.update("INSERT INTO article VALUES(?,1,?,'synthetic',?,'redis',0,NULL,0,1,0,0)", id, title, text); }
    private int countAttempts(String request) { return Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) FROM wiki_compile_attempt WHERE request_id=?", Integer.class, request)); }
    private String resource() { return state.path("resource").asText(); }
    private String budgetId() { return state.path("budgetId").asText(); }
    private Path paidLedger() { return OUT.resolve("runtime/paid/attempts.ledger"); }
    private ReleaseProviderBudget paidBudget() { return new ReleaseProviderBudget(paidLedger().toString(), budgetId(), 0, 3); }
    private void verifyOwner() throws Exception { assertEquals(state.path("owner").asText(), JSON.readTree(docker("inspect", "--format", "{{json .Config.Labels}}", resource())).path("chen.wiki.r2").asText()); }
    private void record(String name, Object evidence) { checks.add(Map.of("check", name, "result", "PASS", "evidence", evidence)); }
    private static void write(Path path, Object value) throws IOException { Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)); }
    private static String fixture(String name) throws IOException { try (var stream = WikiRealProviderAcceptanceIT.class.getResourceAsStream("/wiki/" + name)) { return new String(Objects.requireNonNull(stream).readAllBytes(), StandardCharsets.UTF_8); } }
    private static int occurrences(String text, String needle) { return (text.length() - text.replace(needle, "").length()) / needle.length(); }
    private static void login() { SysUser user = new SysUser(); user.setId(1); user.setUsername("synthetic-r2-admin"); user.setSysPermissions(List.of()); SysRole role = new SysRole(); role.setRole("admin"); user.setSysRoles(List.of(role)); SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(new LoginUser(user), null, List.of())); }
    private static String docker(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker")); command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new FutureTask<>(() -> new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)); Thread.ofVirtual().start(output);
        if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("R2_DOCKER_TIMEOUT"); }
        String body = output.get(5, TimeUnit.SECONDS); if (process.exitValue() != 0) throw new IOException("R2_DOCKER_FAILED"); return body;
    }

    private static final class MockProvider implements AutoCloseable {
        final HttpServer server;
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        MockProvider(boolean unknown) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/chat/completions", exchange -> {
                try {
                    JsonNode request = JSON.readTree(exchange.getRequestBody()); requests.add(request);
                    assertEquals(2048, request.path("max_tokens").asInt()); assertEquals("disabled", request.path("thinking").path("type").asText());
                    String content = respond(request.path("messages").get(0).path("content").asText(), request.path("messages").get(1).path("content").asText());
                    Map<String, Object> response = new LinkedHashMap<>(Map.of("id", "r2-loopback", "object", "chat.completion", "created", 1, "model", MODEL,
                            "choices", List.of(Map.of("index", 0, "finish_reason", "stop", "message", Map.of("role", "assistant", "content", content)))));
                    if (!unknown) response.put("usage", Map.of("prompt_tokens", 100, "completion_tokens", 30, "total_tokens", 130));
                    byte[] bytes = JSON.writeValueAsBytes(response); exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            }); server.start();
        }
        URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions"); }
        public void close() { server.stop(0); }
    }
    private static String respond(String system, String data) throws IOException {
        JsonNode input = JSON.readTree(data); var result = JSON.createObjectNode();
        if (system.startsWith("WIKI_MAP")) {
            var facts = result.putArray("facts"); FACTS.forEach((key, value) -> { if (input.path("text").asText().contains(value)) facts.addObject().put("topic", "redis").put("key", key).put("value", value).put("quote", value); });
        } else {
            result.put("title", "Redis缓存过期与一致性（合成草稿）"); var summary = result.putArray("summary").addObject();
            summary.put("text", "TTL不是强一致性保证；本示例同时约束缓存过期、更新失效、删除补偿与并发回填。");
            var references = summary.putArray("facts"); input.path("facts").forEach(f -> references.add(f.path("id").asText()));
            var claims = result.putArray("sections").addObject().put("heading", "共同事实与独立知识点").putArray("claims");
            input.path("facts").forEach(f -> claims.addObject().put("text", f.path("value").asText()).putArray("facts").add(f.path("id").asText()));
            result.putArray("related");
        }
        return JSON.writeValueAsString(result);
    }
}
