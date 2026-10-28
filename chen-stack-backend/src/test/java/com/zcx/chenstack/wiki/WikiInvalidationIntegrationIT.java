package com.zcx.chenstack.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.domain.entity.LoginUser;
import com.zcx.chenstack.domain.entity.SysRole;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.rag.CatalogLifecycle;
import com.zcx.chenstack.rag.KnowledgeCatalog;
import com.zcx.chenstack.rag.KnowledgeDocument;
import com.zcx.chenstack.security.AdminSecurity;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.zcx.chenstack.wiki.WikiFoundation.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in; only a cached image, a unique network/container, and generated synthetic credentials. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WikiInvalidationIntegrationIT {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private static final SourceKey ARTICLE = new SourceKey(SourceType.ARTICLE, "1");
    private static final SourceKey HELP = new SourceKey(SourceType.PLATFORM_HELP, "help-1");
    @TempDir static Path temporary;
    private final String id = UUID.randomUUID().toString().replace("-", "");
    private final String name = "chen-wiki-invalidation-" + id;
    private final String password = UUID.randomUUID().toString();
    private int port;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager manager;
    private CatalogLifecycle catalog;
    private WikiFoundation wiki;

    @BeforeAll void startIsolatedMysqlAndApplyExplicitMigration() throws Exception {
        assertEquals("true", System.getProperty("chen.wiki.it"), "Explicit isolated integration opt-in required");
        docker("image", "inspect", "mysql:8.0");
        docker("network", "create", "--label", "chen.wiki.invalidation=" + id, name);
        Path env = temporary.resolve("synthetic-mysql.env");
        Files.writeString(env, "MYSQL_ROOT_PASSWORD=" + password + "\nMYSQL_ROOT_HOST=%\n");
        docker("run", "--pull=never", "--detach", "--name", name, "--label", "chen.wiki.invalidation=" + id,
                "--network", name, "--dns", "127.0.0.1", "--publish", "127.0.0.1::3306",
                "--tmpfs", "/var/lib/mysql:rw,nosuid,size=512m", "--env-file", env.toString(), "mysql:8.0");
        String binding = docker("port", name, "3306/tcp").strip();
        assertTrue(binding.startsWith("127.0.0.1:"));
        port = Integer.parseInt(binding.substring(binding.lastIndexOf(':') + 1));
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        boolean ready = false;
        while (System.nanoTime() < deadline) {
            try (var connection = DriverManager.getConnection(url("mysql"), "root", password)) {
                connection.createStatement().execute("CREATE DATABASE chen_wiki_invalidation CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
                ready = true;
                break;
            } catch (Exception starting) {
                Thread.sleep(250);
            }
        }
        assertTrue(ready, "Owned MySQL must become ready");
        var dataSource = new DriverManagerDataSource(url("chen_wiki_invalidation"), "root", password);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.setQueryTimeout(5);
        manager = new DataSourceTransactionManager(dataSource);
        apply("../sql/g2_rag_catalog.sql");
        apply("../sql/g3_wiki_foundation.sql");
        jdbc.execute("""
                CREATE TABLE article (
                    id INT PRIMARY KEY,user_id INT NOT NULL,title VARCHAR(255) NOT NULL,
                    description TEXT,content MEDIUMTEXT,tag VARCHAR(255),reprint_type TINYINT,reprint_url TEXT,
                    edit_status TINYINT,examine_status TINYINT,visible_range TINYINT,is_deleted TINYINT,
                    read_count INT NOT NULL DEFAULT 0,comment_count INT NOT NULL DEFAULT 0,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
                """);
        apply("../sql/g3_wiki_invalidation.sql");
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()", Integer.class));
        System.out.println("WIKI_INVALIDATION_MYSQL_VERSION=" + jdbc.queryForObject("SELECT VERSION()", String.class));
    }

    @BeforeEach void resetOwnedFixture() throws Exception {
        for (String table : List.of("wiki_draft", "wiki_job_source", "wiki_compile_job", "wiki_source_snapshot",
                "wiki_source", "article", "rag_source_state")) {
            jdbc.update("DELETE FROM " + table);
        }
        insertArticle();
        KnowledgeDocument help = new KnowledgeDocument("help-1", "合成帮助", "仅用于隔离测试的公开帮助正文", "v1",
                "PLATFORM_HELP", "/editor", "PUBLIC", "DOCUMENT_REVIEWED", Instant.EPOCH, List.of("合成"));
        jdbc.update("""
                INSERT INTO rag_source_state(source_id,version,document_json,content_sha256,generation,tombstone,deleted,version_history)
                VALUES('help-1','v1',?,?,0,FALSE,FALSE,'{}')
                """, JSON.writeValueAsString(help), "f".repeat(64));
        catalog = new KnowledgeCatalog(List.of(help)).lifecycle();
        wiki = new WikiFoundation(jdbc, manager, new WikiSourceReader(jdbc, catalog, JSON), new AdminSecurity());
        SysUser user = new SysUser();
        user.setId(1);
        user.setUsername("synthetic-invalidation-operator");
        user.setSysPermissions(List.of());
        SysRole role = new SysRole();
        role.setRole("admin");
        user.setSysRoles(List.of(role));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginUser(user), null, List.of()));
    }

    @AfterEach void clearIdentity() {
        if (catalog != null) {
            catalog.close();
        }
        SecurityContextHolder.clearContext();
    }

    @AfterAll void removeOnlyOwnedContainerAndNetwork() throws Exception {
        Set<String> containers = Set.copyOf(docker("ps", "--all", "--filter", "label=chen.wiki.invalidation=" + id,
                "--format", "{{.Names}}").lines().toList());
        if (containers.contains(name)) {
            assertEquals(id, JSON.readTree(docker("inspect", "--format", "{{json .Config.Labels}}", name))
                    .get("chen.wiki.invalidation").asText());
            docker("rm", "--force", "--volumes", name);
        }
        Set<String> networks = Set.copyOf(docker("network", "ls", "--filter", "label=chen.wiki.invalidation=" + id,
                "--format", "{{.Name}}").lines().toList());
        if (networks.contains(name)) {
            assertEquals(id, JSON.readTree(docker("network", "inspect", "--format", "{{json .Labels}}", name))
                    .get("chen.wiki.invalidation").asText());
            docker("network", "rm", name);
        }
        assertTrue(docker("ps", "--all", "--filter", "label=chen.wiki.invalidation=" + id, "--format", "{{.Names}}").isBlank());
        System.out.println("WIKI_INVALIDATION_RESOURCES_REMOVED=" + name);
    }

    @Test void transientWithdrawalWithoutAnyReadRequiresNewExplicitAuthorization() {
        Snapshot original = draft(ARTICLE, "before-withdrawal");
        jdbc.update("UPDATE article SET visible_range=1 WHERE id=1");
        jdbc.update("UPDATE article SET visible_range=0 WHERE id=1");
        assertOldDraftRevoked(original, "before-withdrawal");
        Snapshot replacement = authorize(ARTICLE);
        assertEquals(original.material().fingerprint(), replacement.material().fingerprint());
        assertTrue(replacement.generation() > original.generation());
        assertNotEquals(original.id(), replacement.id());
        wiki.prepare("after-regrant", List.of(replacement.id()), "c".repeat(64));
        wiki.saveDraft("after-regrant", "# 新授权草稿");
        assertEquals("# 新授权草稿", wiki.readDraft("after-regrant").markdown());
        assertThrows(SecurityException.class, () -> wiki.readDraft("before-withdrawal"));
    }

    @Test void editThenRestoreOriginalBytesCannotResurrectOldDraft() {
        Snapshot original = draft(ARTICLE, "before-edit");
        jdbc.update("UPDATE article SET content='<p>changed</p>' WHERE id=1");
        jdbc.update("UPDATE article SET content='<p>知识正文 /Cache/Data</p>' WHERE id=1");
        assertOldDraftRevoked(original, "before-edit");
        assertEquals(original.material().fingerprint(), wiki.inspect(ARTICLE).fingerprint());
    }

    @Test void allCanonicalAndEligibilityFieldsInvalidateOnActualChange() {
        for (String assignment : List.of("user_id=2", "title='新标题'", "description=NULL", "tag='Java'",
                "reprint_type=1", "reprint_url='https://example.invalid/source'", "edit_status=1",
                "examine_status=2", "visible_range=2", "is_deleted=1")) {
            jdbc.update("DELETE FROM article WHERE id=1");
            insertArticle();
            String job = "field-" + UUID.randomUUID();
            Snapshot original = draft(ARTICLE, job);
            jdbc.update("UPDATE article SET " + assignment + " WHERE id=1");
            assertOldDraftRevoked(original, job);
        }
    }

    @Test void caseOnlyEditIsDetectedEvenUnderProductionStyleInsensitiveCollation() {
        Snapshot original = draft(ARTICLE, "case-only");
        jdbc.update("UPDATE article SET content='<p>知识正文 /cache/data</p>' WHERE id=1");
        assertOldDraftRevoked(original, "case-only");
    }

    @Test void trafficUpdatesAndExactNoopUpdatesPreserveAuthorization() {
        Snapshot original = draft(ARTICLE, "traffic-only");
        jdbc.update("UPDATE article SET read_count=read_count+1,comment_count=comment_count+1,update_time=NOW() WHERE id=1");
        jdbc.update("UPDATE article SET content=content,description=description,visible_range=0 WHERE id=1");
        assertEquals(original.generation(), generation(original));
        assertEquals(1, active(original));
        assertEquals("# 合成草稿", wiki.readDraft("traffic-only").markdown());
    }

    @Test void rolledBackArticleAndHelpChangesRollBackTheirInvalidation() {
        Snapshot article = draft(ARTICLE, "article-rollback");
        Snapshot help = draft(HELP, "help-rollback");
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.update("UPDATE article SET is_deleted=1 WHERE id=1");
            jdbc.update("UPDATE rag_source_state SET tombstone=TRUE WHERE source_id='help-1'");
            assertEquals(0, active(article));
            assertEquals(0, active(help));
            status.setRollbackOnly();
        });
        assertEquals(article.generation(), generation(article));
        assertEquals(help.generation(), generation(help));
        assertEquals("# 合成草稿", wiki.readDraft("article-rollback").markdown());
        assertEquals("# 合成草稿", wiki.readDraft("help-rollback").markdown());
    }

    @Test void physicalArticleDeleteAndReinsertCannotRestoreOldAuthorization() {
        Snapshot original = draft(ARTICLE, "article-delete");
        jdbc.update("DELETE FROM article WHERE id=1");
        insertArticle();
        assertOldDraftRevoked(original, "article-delete");
        assertEquals(original.material().fingerprint(), wiki.inspect(ARTICLE).fingerprint());
    }

    @Test void helpTransientTombstoneAndDeleteRequireReauthorization() {
        Snapshot original = draft(HELP, "help-withdrawal");
        jdbc.update("UPDATE rag_source_state SET tombstone=TRUE WHERE source_id='help-1'");
        jdbc.update("UPDATE rag_source_state SET tombstone=FALSE WHERE source_id='help-1'");
        assertOldDraftRevoked(original, "help-withdrawal");
        Snapshot replacement = draft(HELP, "help-delete");
        jdbc.update("DELETE FROM rag_source_state WHERE source_id='help-1'");
        assertOldDraftRevoked(replacement, "help-delete");
    }

    @Test void helpVersionGenerationAndJsonEditsInvalidateEvenIfRestoredBeforeReading() throws Exception {
        for (String assignment : List.of("version='v2'", "generation=generation+1", "content_sha256=REPEAT('a',64)",
                "document_json=JSON_SET(document_json,'$.text','更改后的合成帮助正文')")) {
            Snapshot original = draft(HELP, "help-" + UUID.randomUUID());
            String job = jdbc.queryForObject("SELECT request_id FROM wiki_job_source WHERE snapshot_id=?", String.class, original.id());
            jdbc.update("UPDATE rag_source_state SET " + assignment + " WHERE source_id='help-1'");
            jdbc.update("UPDATE rag_source_state SET version='v1',generation=0,content_sha256=REPEAT('f',64),document_json=? WHERE source_id='help-1'",
                    JSON.writeValueAsString(catalog.snapshot().source("help-1")));
            assertOldDraftRevoked(original, job);
        }
    }

    private void assertOldDraftRevoked(Snapshot original, String job) {
        assertEquals(0, active(original));
        assertEquals(original.generation() + 1, generation(original));
        assertThrows(SecurityException.class, () -> wiki.readDraft(job));
    }

    private int active(Snapshot source) {
        return jdbc.queryForObject("SELECT active FROM wiki_source WHERE id=?", Integer.class, source.sourceId());
    }

    private long generation(Snapshot source) {
        return jdbc.queryForObject("SELECT generation FROM wiki_source WHERE id=?", Long.class, source.sourceId());
    }

    private Snapshot authorize(SourceKey source) {
        return wiki.authorize(source, wiki.inspect(source).fingerprint());
    }

    private Snapshot draft(SourceKey source, String job) {
        Snapshot snapshot = authorize(source);
        wiki.prepare(job, List.of(snapshot.id()), "c".repeat(64));
        wiki.saveDraft(job, "# 合成草稿");
        return snapshot;
    }

    private void insertArticle() {
        jdbc.update("""
                INSERT INTO article(id,user_id,title,description,content,tag,reprint_type,reprint_url,edit_status,examine_status,visible_range,is_deleted)
                VALUES(1,1,'合成技术资料','隔离样本','<p>知识正文 /Cache/Data</p>','java',0,NULL,0,1,0,0)
                """);
    }

    private void apply(String file) throws IOException {
        String sql = Files.readString(Path.of(file), StandardCharsets.UTF_8).replaceAll("(?m)^--[^\\r\\n]*", "");
        for (String statement : sql.split(";")) {
            if (!statement.isBlank()) {
                jdbc.execute(statement);
            }
        }
    }

    private String url(String schema) {
        if (!Set.of("mysql", "chen_wiki_invalidation").contains(schema) || port <= 0) {
            throw new IllegalArgumentException("Invalid owned test database");
        }
        return "jdbc:mysql://127.0.0.1:" + port + "/" + schema + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
    }

    private static String docker(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("WIKI_INVALIDATION_DOCKER_TIMEOUT");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IOException("WIKI_INVALIDATION_DOCKER_FAILED: " + output.substring(0, Math.min(500, output.length())));
        }
        return output;
    }
}
