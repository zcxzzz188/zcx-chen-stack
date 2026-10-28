package com.zcx.chenstack.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.domain.entity.LoginUser;
import com.zcx.chenstack.domain.entity.SysRole;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.rag.*;
import com.zcx.chenstack.security.AdminSecurity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

import static com.zcx.chenstack.wiki.WikiFoundation.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: mvn -o -Dtest=WikiFoundationIntegrationIT -Dchen.wiki.it=true test.
 * Uses only a cached mysql:8.0 image in a uniquely labeled container/network; no production connections.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WikiFoundationIntegrationIT {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final SourceKey ARTICLE = new SourceKey(SourceType.ARTICLE, "1");
    private static final SourceKey HELP = new SourceKey(SourceType.PLATFORM_HELP, "help-1");
    private static final String CONTRACT = "c".repeat(64);
    @TempDir static Path temporary;
    private DockerMysql docker;
    private JdbcTemplate jdbc;
    private DriverManagerDataSource dataSource;
    private DataSourceTransactionManager manager;
    private CatalogLifecycle catalog;
    private WikiFoundation wiki;
    private List<KnowledgeDocument> help;

    @BeforeAll void startOwnedDatabase() throws Exception {
        assertEquals("true", System.getProperty("chen.wiki.it"), "Explicit isolated integration opt-in required");
        docker = new DockerMysql(temporary);
        docker.start();
        try (var c = DriverManager.getConnection(docker.url("mysql"), "root", docker.password);
             var statement = c.createStatement()) {
            statement.execute("CREATE DATABASE chen_wiki_it CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }
        dataSource = new DriverManagerDataSource(docker.url("chen_wiki_it"), "root", docker.password);
        jdbc = new JdbcTemplate(dataSource); jdbc.setQueryTimeout(5);
        manager = new DataSourceTransactionManager(dataSource);
        apply(Path.of("../sql/g2_rag_catalog.sql"));
        apply(Path.of("../sql/g3_wiki_foundation.sql"));
        jdbc.execute("""
                CREATE TABLE article (
                    id INT PRIMARY KEY, user_id INT NOT NULL, title VARCHAR(50) NOT NULL,
                    description VARCHAR(200), content MEDIUMTEXT, tag VARCHAR(255),
                    reprint_type TINYINT, reprint_url VARCHAR(400), edit_status TINYINT,
                    examine_status TINYINT, visible_range TINYINT, is_deleted TINYINT,
                    read_count INT DEFAULT 0, update_time DATETIME DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB
                """);
        System.out.println("WIKI_IT_MYSQL_VERSION=" + jdbc.queryForObject("SELECT VERSION()", String.class));
    }

    @BeforeEach void resetOnlyOwnedFixture() throws Exception {
        for (String table : List.of("wiki_draft","wiki_job_source","wiki_compile_job","wiki_source_snapshot","wiki_source","article","rag_source_state","rag_catalog_state")) {
            jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("""
                INSERT INTO article(id,user_id,title,description,content,tag,reprint_type,reprint_url,edit_status,examine_status,visible_range,is_deleted)
                VALUES(1,1,'合成技术资料','隔离样本','<p>知识正文</p><script>unsafe()</script>','Java',0,NULL,0,1,0,0)
                """);
        help = new ArrayList<>();
        for (int i = 1; i <= 39; i++) {
            var d = new KnowledgeDocument("help-" + i, "合成帮助" + i, "仅用于隔离测试的公开帮助正文" + i, "v1",
                    "PLATFORM_HELP", "/editor", "PUBLIC", "DOCUMENT_REVIEWED", Instant.EPOCH, List.of("合成"));
            help.add(d);
            jdbc.update("""
                    INSERT INTO rag_source_state(source_id,version,document_json,content_sha256,generation,tombstone,deleted,version_history)
                    VALUES(?,? ,?, ?,0,FALSE,FALSE,'{}')
                    """, d.sourceId(), d.version(), JSON.writeValueAsString(d), "f".repeat(64));
        }
        catalog = new KnowledgeCatalog(help).lifecycle();
        wiki = freshFoundation();
        login(true);
    }

    @AfterEach void clearIdentity() {
        if (catalog != null) catalog.close();
        SecurityContextHolder.clearContext();
    }

    @AfterAll void cleanOnlyOwnedResources() throws Exception {
        if (docker != null) docker.close();
    }

    @Test void strictAdmissionRejectsEveryNonpublicOrUnreviewedStateWithoutWritingSnapshots() {
        for (Object[] change : List.of(
                new Object[]{"visible_range",1},new Object[]{"visible_range",2},new Object[]{"visible_range",3},
                new Object[]{"visible_range",null},new Object[]{"examine_status",0},new Object[]{"examine_status",2},
                new Object[]{"edit_status",1},new Object[]{"edit_status",2},new Object[]{"is_deleted",1})) {
            jdbc.update("UPDATE article SET visible_range=0,examine_status=1,edit_status=0,is_deleted=0 WHERE id=1");
            jdbc.update("UPDATE article SET " + change[0] + "=? WHERE id=1", change[1]);
            assertThrows(SecurityException.class, () -> wiki.inspect(ARTICLE));
            assertThrows(SecurityException.class, () -> wiki.authorize(ARTICLE, "0".repeat(64)));
            assertEquals(0, count("wiki_source"));
            assertEquals(0, count("wiki_source_snapshot"));
        }
        assertThrows(SecurityException.class, () -> wiki.inspect(new SourceKey(SourceType.ARTICLE,"999")));
    }

    @Test void validSourceStillRequiresExplicitPinnedAuthorizationAndSnapshotsAreIdempotent() {
        assertEquals(0, count("wiki_source"));
        Candidate candidate = wiki.inspect(ARTICLE);
        assertFalse(candidate.text().contains("unsafe"));
        assertTrue(candidate.rawJson().contains("unsafe"));
        Snapshot first = wiki.authorize(ARTICLE, candidate.fingerprint());
        Snapshot again = wiki.authorize(ARTICLE, candidate.fingerprint());
        assertEquals(first, again);
        assertEquals(1, first.generation());
        assertEquals(1, count("wiki_source_snapshot"));
        jdbc.update("UPDATE article SET read_count=read_count+1 WHERE id=1");
        assertEquals(candidate.fingerprint(), wiki.inspect(ARTICLE).fingerprint());
        assertEquals(39, count("rag_source_state"));
    }

    @Test void normalUserCannotGrantOrInspectEvenTheirOwnArticle() {
        login(false);
        assertThrows(SecurityException.class, () -> wiki.inspect(ARTICLE));
        assertThrows(SecurityException.class, () -> wiki.authorize(ARTICLE, "a".repeat(64)));
        assertThrows(SecurityException.class, () -> wiki.revoke(ARTICLE));
        assertThrows(SecurityException.class, () -> wiki.prepare("forbidden", List.of(1L), CONTRACT));
        assertEquals(0, count("wiki_source"));
    }

    @Test void staleHumanHashCannotAuthorizeEditedContentAndSameSecondEditsCreateNewVersions() {
        Candidate old = wiki.inspect(ARTICLE);
        jdbc.update("UPDATE article SET content='<p>第一次修改</p>' WHERE id=1");
        assertThrows(SecurityException.class, () -> wiki.authorize(ARTICLE, old.fingerprint()));
        assertEquals(0, count("wiki_source"));
        Snapshot one = authorize(ARTICLE);
        jdbc.update("UPDATE article SET content='<p>第二次修改</p>' WHERE id=1");
        Snapshot two = authorize(ARTICLE);
        assertNotEquals(one.id(), two.id());
        assertNotEquals(one.material().fingerprint(), two.material().fingerprint());
        assertTrue(two.generation() > one.generation());
        assertEquals(one.material().rawJson(), jdbc.queryForObject(
                "SELECT raw_json FROM wiki_source_snapshot WHERE id=?", String.class, one.id()));
    }

    @Test void repeatedCompileIntentIsIdempotentAndConflictingContractRollsBack() {
        Snapshot source = authorize(ARTICLE);
        Job job = wiki.prepare("same-request", List.of(source.id()), CONTRACT);
        assertEquals(job, wiki.prepare("same-request", List.of(source.id(), source.id()), CONTRACT));
        assertThrows(IllegalStateException.class, () -> wiki.prepare("same-request", List.of(source.id()), "d".repeat(64)));
        assertEquals(1, count("wiki_compile_job"));
        assertEquals(1, count("wiki_job_source"));
        assertEquals(List.of("same-request"), wiki.affectedJobs(ARTICLE));
    }

    @Test void twoServiceInstancesShareIdempotencyWithoutInMemoryLocks() throws Exception {
        Snapshot source = authorize(ARTICLE);
        try (var workers = Executors.newFixedThreadPool(2)) {
            List<Future<Job>> attempts = new ArrayList<>();
            for (int i=0;i<2;i++) attempts.add(workers.submit(() -> {
                login(true);
                try { return freshFoundation().prepare("concurrent", List.of(source.id()), CONTRACT); }
                finally { SecurityContextHolder.clearContext(); }
            }));
            assertEquals(attempts.getFirst().get(10, TimeUnit.SECONDS), attempts.getLast().get(10, TimeUnit.SECONDS));
        }
        assertEquals(1, count("wiki_compile_job"));
        assertEquals(1, count("wiki_job_source"));
    }

    @Test void allOriginChangesBlockExistingInputsAndPersistInvalidationBeforeThrowing() {
        Snapshot source = authorize(ARTICLE);
        wiki.prepare("old", List.of(source.id()), CONTRACT);
        jdbc.update("UPDATE article SET visible_range=1 WHERE id=1");
        assertThrows(SecurityException.class, () -> wiki.inputs("old"));
        assertEquals(0, jdbc.queryForObject("SELECT active FROM wiki_source WHERE id=?", Integer.class, source.sourceId()));
        jdbc.update("UPDATE article SET visible_range=0 WHERE id=1");
        assertThrows(SecurityException.class, () -> wiki.inputs("old"));
        Snapshot replacement = authorize(ARTICLE);
        assertNotEquals(source.id(), replacement.id());
        assertThrows(SecurityException.class, () -> wiki.inputs("old"));
        wiki.prepare("new", List.of(replacement.id()), CONTRACT);
        assertEquals(1, wiki.inputs("new").sources().size());
    }

    @Test void editsReviewDeletionAndOwnerChangesBlockOldDraftsWithoutBusinessWriteHooks() {
        for (String update : List.of("content='<p>更改</p>'", "title='新标题'", "user_id=2", "tag='新标签'",
                "examine_status=2", "edit_status=1", "is_deleted=1")) {
            jdbc.update("""
                    UPDATE article SET content='<p>知识正文</p><script>unsafe()</script>',title='合成技术资料',
                    user_id=1,tag='Java',examine_status=1,edit_status=0,is_deleted=0 WHERE id=1
                    """);
            Snapshot source = authorize(ARTICLE);
            String id = "job-" + UUID.randomUUID();
            wiki.prepare(id, List.of(source.id()), CONTRACT);
            wiki.saveDraft(id, "# 合成草稿");
            jdbc.update("UPDATE article SET " + update + " WHERE id=1");
            assertThrows(SecurityException.class, () -> wiki.readDraft(id));
            assertThrows(SecurityException.class, () -> wiki.saveDraft(id, "# 合成草稿"));
        }
    }

    @Test void revokeIsIdempotentAndRegrantDoesNotResurrectOldVersions() {
        Snapshot source = authorize(ARTICLE);
        wiki.prepare("revoked", List.of(source.id()), CONTRACT);
        wiki.revoke(ARTICLE);
        long generation = jdbc.queryForObject("SELECT generation FROM wiki_source WHERE id=?", Long.class, source.sourceId());
        wiki.revoke(ARTICLE);
        assertEquals(generation, jdbc.queryForObject("SELECT generation FROM wiki_source WHERE id=?", Long.class, source.sourceId()));
        authorize(ARTICLE);
        assertThrows(SecurityException.class, () -> wiki.inputs("revoked"));
    }

    @Test void restartReconstructsInputsAndDraftStaysDraftDespiteCompilerPublicationClaims() {
        Snapshot source = authorize(ARTICLE);
        wiki.prepare("restart", List.of(source.id()), CONTRACT);
        assertEquals(source, freshFoundation().inputs("restart").sources().getFirst());
        String output = "# 合成草稿\nstatus: PUBLISHED\n仅是模型文本";
        Draft draft = freshFoundation().saveDraft("restart", output);
        assertEquals(draft, wiki.saveDraft("restart", output));
        assertEquals("DRAFT", jdbc.queryForObject("SELECT state FROM wiki_draft WHERE request_id='restart'", String.class));
        assertEquals(draft, freshFoundation().readDraft("restart"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE wiki_draft SET state='PUBLISHED' WHERE request_id='restart'"));
        assertThrows(IllegalStateException.class, () -> wiki.saveDraft("restart", "不同候选"));
    }

    @Test void databaseFailureRollsBackAuthorizationAndDraftJobTogether() {
        jdbc.execute("CREATE TRIGGER reject_snapshot BEFORE INSERT ON wiki_source_snapshot FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic snapshot failure'");
        try { assertThrows(DataAccessException.class, () -> authorize(ARTICLE)); }
        finally { jdbc.execute("DROP TRIGGER reject_snapshot"); }
        assertEquals(0, count("wiki_source"));
        assertEquals(0, count("wiki_source_snapshot"));
        Snapshot source = authorize(ARTICLE);
        wiki.prepare("fault", List.of(source.id()), CONTRACT);
        jdbc.execute("CREATE TRIGGER reject_ready BEFORE UPDATE ON wiki_compile_job FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='synthetic job failure'");
        try { assertThrows(DataAccessException.class, () -> wiki.saveDraft("fault", "草稿正文")); }
        finally { jdbc.execute("DROP TRIGGER reject_ready"); }
        assertEquals(0, count("wiki_draft"));
        assertEquals("QUEUED", jdbc.queryForObject("SELECT state FROM wiki_compile_job WHERE request_id='fault'", String.class));
    }

    @Test void snapshotTamperingAndCancellationCannotReleaseData() {
        Snapshot source = authorize(ARTICLE);
        wiki.prepare("tamper", List.of(source.id()), CONTRACT);
        jdbc.update("UPDATE wiki_source_snapshot SET parsed_text='forged' WHERE id=?", source.id());
        assertThrows(SecurityException.class, () -> wiki.inputs("tamper"));
        jdbc.update("UPDATE wiki_source_snapshot SET parsed_text=? WHERE id=?", source.material().text(), source.id());
        wiki.cancel("tamper");
        assertThrows(IllegalStateException.class, () -> wiki.inputs("tamper"));
        assertThrows(IllegalStateException.class, () -> wiki.saveDraft("tamper", "late output"));
        assertEquals(0, count("wiki_draft"));
    }

    @Test void helpRequiresBothCurrentCatalogAndPersistedPublishedSourceWithoutChangingG2() throws Exception {
        List<String> before = jdbc.queryForList("SELECT document_json FROM rag_source_state ORDER BY source_id", String.class);
        Snapshot source = authorize(HELP);
        wiki.prepare("help", List.of(source.id()), CONTRACT);
        assertEquals(source, wiki.inputs("help").sources().getFirst());
        assertEquals(before, jdbc.queryForList("SELECT document_json FROM rag_source_state ORDER BY source_id", String.class));
        assertEquals(39, count("rag_source_state"));
        jdbc.update("UPDATE rag_source_state SET tombstone=TRUE WHERE source_id='help-1'");
        assertThrows(SecurityException.class, () -> wiki.inputs("help"));
    }

    @Test void catalogRevocationIsRejectedEvenWhenOldDatabasePayloadSurvives() {
        Snapshot source = authorize(HELP);
        wiki.prepare("catalog-revoke", List.of(source.id()), CONTRACT);
        catalog.revoke("help-1", catalog.snapshot().epoch());
        assertThrows(SecurityException.class, () -> wiki.inputs("catalog-revoke"));
    }

    @Test void helpWithUnpublishedOrMismatchedVersionIsNotAdmitted() {
        jdbc.update("UPDATE rag_source_state SET version='obsolete' WHERE source_id='help-1'");
        assertThrows(SecurityException.class, () -> wiki.inspect(HELP));
        var pending = new KnowledgeDocument("help-1","待审帮助","合成正文","v1","PLATFORM_HELP","/editor",
                "NOT_PUBLISHED","CONTENT_REVIEW_REQUIRED",Instant.EPOCH,List.of());
        catalog = new KnowledgeCatalog(List.of(pending)).lifecycle();
        wiki = freshFoundation();
        assertThrows(SecurityException.class, () -> wiki.inspect(HELP));
        assertEquals(0, count("wiki_source"));
    }

    @Test void sourceGateDoesNotInheritAnAmbientRepeatableReadSnapshot() {
        Snapshot source = authorize(ARTICLE);
        wiki.prepare("rr", List.of(source.id()), CONTRACT);
        TransactionTemplate outer = new TransactionTemplate(manager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status -> {
            assertEquals(0, jdbc.queryForObject("SELECT visible_range FROM article WHERE id=1", Integer.class));
            try (var thread = Executors.newSingleThreadExecutor()) {
                assertEquals(1, thread.submit(() -> jdbc.update("UPDATE article SET visible_range=1 WHERE id=1")).get(5,TimeUnit.SECONDS));
            } catch (Exception e) { throw new AssertionError(e); }
            assertEquals(0, jdbc.queryForObject("SELECT visible_range FROM article WHERE id=1", Integer.class));
            assertThrows(SecurityException.class, () -> wiki.inputs("rr"));
        });
    }

    @Test void immutableSelectionAlsoUsesItsOwnCurrentTransaction() {
        TransactionTemplate outer = new TransactionTemplate(manager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status -> {
            assertEquals(0, count("wiki_source_snapshot"));
            Snapshot source = authorize(ARTICLE);
            wiki.prepare("rr-created", List.of(source.id()), CONTRACT);
            assertEquals(0, count("wiki_source_snapshot"));
            assertEquals(source, wiki.inputs("rr-created").sources().getFirst());
        });
    }

    @Test void multipleHelpSourcesKeepAuthorityUntilTransactionAndCallbackComplete() throws Exception {
        catalog.close();
        jdbc.update("DELETE FROM rag_source_state");
        JdbcCatalogStore.initialize(dataSource,help);
        catalog = KnowledgeCatalog.restore(JdbcCatalogStore.open(dataSource)).lifecycle();
        wiki = freshFoundation();
        List<String> before = jdbc.queryForList("SELECT document_json FROM rag_source_state ORDER BY source_id",String.class);
        Snapshot first = authorize(HELP);
        Snapshot second = authorize(new SourceKey(SourceType.PLATFORM_HELP,"help-2"));
        wiki.prepare("multi-help", List.of(first.id(),second.id()), CONTRACT);
        long epoch = catalog.snapshot().epoch();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), revokeStarted = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            Future<Integer> reading = threads.submit(() -> wiki.withCurrentInputs("multi-help", input -> {
                assertTrue(Thread.holdsLock(catalog));
                entered.countDown();
                try { assertTrue(release.await(4,TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return input.sources().size();
            }));
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            Future<Long> unavailable = threads.submit(() -> {
                revokeStarted.countDown();
                return catalog.markUnavailable(epoch);
            });
            assertTrue(revokeStarted.await(2,TimeUnit.SECONDS));
            try { assertThrows(TimeoutException.class, () -> unavailable.get(250,TimeUnit.MILLISECONDS)); }
            finally { release.countDown(); }
            assertEquals(2, reading.get(4,TimeUnit.SECONDS));
            assertEquals(epoch+1, unavailable.get(4,TimeUnit.SECONDS));
            assertEquals(epoch+1, jdbc.queryForObject("SELECT generation FROM rag_catalog_state WHERE id=1",Long.class));
            assertEquals(before, jdbc.queryForList("SELECT document_json FROM rag_source_state ORDER BY source_id",String.class));
            assertEquals(39, count("rag_source_state"));
            assertThrows(SecurityException.class, () -> wiki.inputs("multi-help"));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM wiki_source WHERE active=TRUE",Integer.class));
        }
    }

    @Test void currentInputBoundaryHoldsRowLocksUntilItsCallbackCompletes() throws Exception {
        Snapshot source = authorize(ARTICLE);
        wiki.prepare("locked", List.of(source.id()), CONTRACT);
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), writeStarted = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            Future<String> reading = threads.submit(() -> wiki.withCurrentInputs("locked", input -> {
                entered.countDown();
                try { assertTrue(release.await(4, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                return input.sources().getFirst().material().text();
            }));
            assertTrue(entered.await(3,TimeUnit.SECONDS));
            Future<Integer> change = threads.submit(() -> {
                writeStarted.countDown();
                return jdbc.update("UPDATE article SET visible_range=1 WHERE id=1");
            });
            assertTrue(writeStarted.await(2,TimeUnit.SECONDS));
            try {
                assertThrows(TimeoutException.class, () -> change.get(250,TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            assertTrue(reading.get(4,TimeUnit.SECONDS).contains("知识正文"));
            assertEquals(1, change.get(4,TimeUnit.SECONDS));
            assertThrows(SecurityException.class, () -> wiki.inputs("locked"));
        }
    }

    private Snapshot authorize(SourceKey key) {
        return wiki.authorize(key, wiki.inspect(key).fingerprint());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private WikiFoundation freshFoundation() {
        return new WikiFoundation(jdbc, manager, new WikiSourceReader(jdbc, catalog, JSON), new AdminSecurity());
    }

    private static void login(boolean admin) {
        SysUser user = new SysUser();
        user.setId(1); user.setUsername("synthetic-wiki-operator"); user.setSysPermissions(List.of());
        SysRole role = new SysRole(); role.setRole(admin ? "admin" : "user");
        user.setSysRoles(List.of(role));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(user), null, List.of()));
    }

    private void apply(Path path) throws IOException {
        String sql = Files.readString(path, StandardCharsets.UTF_8).replaceAll("(?m)^--[^\r\n]*", "");
        for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
    }

    private static final class DockerMysql implements AutoCloseable {
        final String id = UUID.randomUUID().toString().replace("-", "");
        final String name = "chen-wiki-it-" + id;
        final String password = UUID.randomUUID().toString();
        final Path temporary;
        int port;
        DockerMysql(Path temporary) { this.temporary = temporary; }

        void start() throws Exception {
            command("image","inspect","mysql:8.0");
            command("network","create","--driver","bridge","--label","chen.wiki.it="+id,name);
            Path env = temporary.resolve("synthetic-mysql.env");
            Files.writeString(env, "MYSQL_ROOT_PASSWORD="+password+"\nMYSQL_ROOT_HOST=%\n");
            command("run","--pull=never","--detach","--name",name,"--label","chen.wiki.it="+id,
                    "--network",name,"--dns","127.0.0.1","--publish","127.0.0.1::3306",
                    "--tmpfs","/var/lib/mysql:rw,nosuid,size=512m","--env-file",env.toString(),"mysql:8.0");
            String binding = command("port",name,"3306/tcp").strip();
            assertTrue(binding.startsWith("127.0.0.1:"));
            port = Integer.parseInt(binding.substring(binding.lastIndexOf(':')+1));
            long deadline = System.nanoTime()+Duration.ofSeconds(90).toNanos();
            while (System.nanoTime()<deadline) {
                try (var ignored = DriverManager.getConnection(url("mysql"),"root",password)) { return; }
                catch (Exception notReady) { Thread.sleep(250); }
            }
            throw new IOException("WIKI_ISOLATED_MYSQL_START_TIMEOUT");
        }

        String url(String schema) {
            if (!Set.of("mysql","chen_wiki_it").contains(schema) || port <= 0) throw new IllegalArgumentException();
            return "jdbc:mysql://127.0.0.1:"+port+"/"+schema+"?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        }

        static String command(String... args) throws Exception {
            var all = new ArrayList<String>(); all.add("docker"); all.addAll(List.of(args));
            Process process = new ProcessBuilder(all).redirectErrorStream(true).start();
            if (!process.waitFor(45,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("WIKI_TEST_DOCKER_TIMEOUT"); }
            String output = new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
            if (process.exitValue()!=0) throw new IOException("WIKI_TEST_DOCKER_FAILED: "+output.substring(0,Math.min(500,output.length())));
            return output;
        }

        @Override public void close() throws Exception {
            Set<String> containers = new HashSet<>(command("ps","--all","--filter","label=chen.wiki.it="+id,"--format","{{.Names}}").lines().toList());
            if (containers.contains(name)) {
                assertEquals(id, JSON.readTree(command("inspect","--format","{{json .Config.Labels}}",name)).get("chen.wiki.it").asText());
                command("rm","--force","--volumes",name);
            }
            Set<String> networks = new HashSet<>(command("network","ls","--filter","label=chen.wiki.it="+id,"--format","{{.Name}}").lines().toList());
            if (networks.contains(name)) {
                assertEquals(id, JSON.readTree(command("network","inspect","--format","{{json .Labels}}",name)).get("chen.wiki.it").asText());
                command("network","rm",name);
            }
            assertTrue(command("ps","--all","--filter","label=chen.wiki.it="+id,"--format","{{.Names}}").isBlank());
            System.out.println("WIKI_IT_RESOURCES_REMOVED="+name);
        }
    }
}

