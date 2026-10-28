package com.zcx.chenstack.wiki;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.config.ChenStackConfig;
import com.zcx.chenstack.controller.*;
import com.zcx.chenstack.domain.entity.*;
import com.zcx.chenstack.domain.enums.StatusEnum;
import com.zcx.chenstack.domain.result.Result;
import com.zcx.chenstack.mapper.*;
import com.zcx.chenstack.rag.KnowledgeCatalog;
import com.zcx.chenstack.security.*;
import com.zcx.chenstack.utils.IpUtils;
import com.zcx.chenstack.utils.JwtUtils;
import org.apache.catalina.Context;
import org.apache.catalina.startup.Tomcat;
import org.apache.tomcat.util.descriptor.web.FilterDef;
import org.apache.tomcat.util.descriptor.web.FilterMap;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real TCP/Tomcat, production MVC/security/Wiki services and isolated MySQL.
 * Identity lookup/MQ/IP are synthetic; final mode attaches an existing real-provider draft. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WikiProductHttpIntegrationIT {
    @TempDir static Path temporary;
    static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    static DriverManagerDataSource dataSource;
    static final String JWT_KEY = UUID.randomUUID().toString();
    static final String SOURCE_TITLE = "P3合成 Redis 来源";
    static final boolean FINAL_HTTP = Boolean.getBoolean("chen.wiki.final.http");
    static final String FINAL_DATABASE = System.getProperty("chen.wiki.final.database", "wiki_r2_acceptance");
    static final Path FINAL_OUT = finalOutput(System.getProperty("chen.wiki.final.batch", ""));
    private final String identity = UUID.randomUUID().toString().replace("-", "");
    private final String resource = "chen-wiki-http-" + identity;
    private final String password = UUID.randomUUID().toString();
    private JdbcTemplate jdbc;
    private Tomcat tomcat;
    private AnnotationConfigWebApplicationContext application;
    private String baseUrl;
    private String adminToken;
    private String userToken;
    private JsonNode finalState;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();

    static Path finalOutput(String batch) {
        if (!batch.matches("(?:[0-9]{2})?")) throw new IllegalArgumentException("FINAL_BATCH_INVALID");
        Path root = Path.of("../.work/G3_FINAL_ENGINEERING_ACCEPTANCE").toAbsolutePath().normalize();
        return batch.isEmpty() ? root : root.resolve("batch-" + batch);
    }

    @BeforeAll void startOwnedMysqlAndActualHttp() throws Exception {
        assertEquals("true", System.getProperty("chen.wiki.it"));
        if (FINAL_HTTP) {
            attachFinalMysql();
        } else {
        docker("image", "inspect", "mysql:8.0");
        docker("network", "create", "--label", "chen.wiki.product.http=" + identity, resource);
        Path environment = temporary.resolve("mysql.env");
        Files.writeString(environment, "MYSQL_ROOT_PASSWORD=" + password + "\nMYSQL_ROOT_HOST=%\n");
        docker("run", "--pull=never", "--detach", "--name", resource, "--label", "chen.wiki.product.http=" + identity,
                "--network", resource, "--dns", "127.0.0.1", "--publish", "127.0.0.1::3306", "--tmpfs", "/var/lib/mysql:rw,nosuid,size=512m",
                "--env-file", environment.toString(), "mysql:8.0");
        String binding = docker("port", resource, "3306/tcp").strip();
        assertTrue(binding.startsWith("127.0.0.1:"));
        String url = "jdbc:mysql://" + binding + "/mysql?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (true) {
            try (var connection = DriverManager.getConnection(url, "root", password)) {
                connection.createStatement().execute("CREATE DATABASE wiki_http CHARACTER SET utf8mb4 COLLATE utf8mb4_bin"); break;
            } catch (java.sql.SQLException notReady) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("OWNED_MYSQL_TIMEOUT");
                Thread.sleep(250);
            }
        }
        dataSource = new DriverManagerDataSource(url.replace("/mysql?", "/wiki_http?"), "root", password);
        jdbc = new JdbcTemplate(dataSource); jdbc.setQueryTimeout(5);
        apply("../sql/g2_rag_catalog.sql"); apply("../sql/g3_wiki_foundation.sql"); apply("../sql/g3_wiki_compiler.sql");
        jdbc.execute("""
                CREATE TABLE article(id INT PRIMARY KEY,user_id INT,title VARCHAR(255),description TEXT,content MEDIUMTEXT,
                tag VARCHAR(100),reprint_type TINYINT,reprint_url TEXT,edit_status TINYINT,examine_status TINYINT,
                visible_range TINYINT,is_deleted TINYINT) ENGINE=InnoDB
                """);
        apply("../sql/g3_wiki_invalidation.sql"); apply("../sql/g3_wiki_product.sql");
        }

        tomcat = new Tomcat(); tomcat.setBaseDir(temporary.resolve("tomcat").toString()); tomcat.setHostname("127.0.0.1"); tomcat.setPort(0);
        tomcat.getConnector().setProperty("address", "127.0.0.1");
        Path web = Files.createDirectories(temporary.resolve("web"));
        Context container = tomcat.addContext("", web.toString());
        container.setParentClassLoader(getClass().getClassLoader());
        application = new AnnotationConfigWebApplicationContext();
        application.getEnvironment().getPropertySources().addFirst(new MapPropertySource("wiki-http-test", Map.of(
                "chen.wiki.enabled", "true", "chen.wiki.product.enabled", "true", "chen.wiki.public-portal-enabled", "true", "chen-stack.jwt", JWT_KEY)));
        application.setServletContext(container.getServletContext()); application.register(TestWeb.class); application.refresh();
        container.getServletContext().setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, application);
        Tomcat.addServlet(container, "dispatcher", new DispatcherServlet(application)).setLoadOnStartup(1);
        container.addServletMappingDecoded("/", "dispatcher");
        FilterDef filter = new FilterDef(); filter.setFilterName("springSecurityFilterChain");
        filter.setFilter(new DelegatingFilterProxy("springSecurityFilterChain", application)); container.addFilterDef(filter);
        FilterMap mapping = new FilterMap(); mapping.setFilterName("springSecurityFilterChain"); mapping.addURLPattern("/*"); container.addFilterMapBefore(mapping);
        tomcat.start(); baseUrl = "http://127.0.0.1:" + tomcat.getConnector().getLocalPort();
        JwtUtils jwt = application.getBean(JwtUtils.class); adminToken = "Bearer " + jwt.createToken(1); userToken = "Bearer " + jwt.createToken(2);
        System.out.println("WIKI_PRODUCT_HTTP=" + baseUrl);
        System.out.println("WIKI_PRODUCT_HTTP_MYSQL=" + jdbc.queryForObject("SELECT VERSION()", String.class));
    }

    @BeforeEach void reset() {
        if (FINAL_HTTP) return;
        jdbc.update("UPDATE wiki_page SET current_revision_id=NULL");
        for (String table : List.of("wiki_review_event", "wiki_revision", "wiki_page", "wiki_compile_topic", "wiki_compile_attempt", "wiki_compile_budget",
                "wiki_draft", "wiki_job_source", "wiki_compile_job", "wiki_source_snapshot", "wiki_source", "article")) jdbc.update("DELETE FROM " + table);
        jdbc.update("""
                INSERT INTO article(id,user_id,title,description,content,tag,reprint_type,reprint_url,edit_status,examine_status,visible_range,is_deleted)
                VALUES(1,1,?,'隔离测试来源','<p>Redis 缓存采用明确的过期时间。</p>','redis',0,NULL,0,1,0,0)
                """, SOURCE_TITLE);
    }

    @Test @DisabledIfSystemProperty(named="chen.wiki.final.http", matches="true")
    void actualJwtAndServerRbacRejectAnonymousOrdinaryAndInvalidTokens() throws Exception {
        assertEquals(401, get("/admin/wiki/access", null).statusCode());
        assertEquals(403, get("/admin/wiki/access", userToken).statusCode());
        assertEquals(401, get("/admin/wiki/access", "Bearer invalid").statusCode());
        assertEquals(200, get("/admin/wiki/access", adminToken).statusCode());
        assertEquals(403, post("/admin/wiki/sources/inspect", userToken, Map.of("type", "ARTICLE", "id", "1")).statusCode());
    }

    @Test @DisabledIfSystemProperty(named="chen.wiki.final.http", matches="true")
    void anonymousDirectoryIsEmptyAndNotCachedAndInvalidObjectsAreRejected() throws Exception {
        var response = get("/wiki/pages?q=" + java.net.URLEncoder.encode("中文", StandardCharsets.UTF_8), null);
        assertEquals(200, response.statusCode()); assertEquals(0, data(response).path("total").asInt());
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(404, get("/wiki/pages/999999", null).statusCode());
        assertEquals(400, get("/wiki/pages/0", null).statusCode());
        assertEquals(400, get("/wiki/pages?size=51", null).statusCode());
        assertFalse(get("/wiki/pages/999999", null).body().contains("SQL"));
    }

    @Test @DisabledIfSystemProperty(named="chen.wiki.final.http", matches="true")
    void invalidCompileAndAuthorizationInputsNeverCreatePaidCallsOrAuthority() throws Exception {
        assertEquals(400, post("/admin/wiki/jobs", adminToken, Map.of("requestId", "bad/id", "snapshotIds", List.of(1))).statusCode());
        assertEquals(400, post("/admin/wiki/sources/authorize", adminToken, Map.of("type", "ARTICLE", "id", "1", "fingerprint", "bad")).statusCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM wiki_compile_attempt", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM wiki_source_snapshot", Integer.class));
    }

    @Test @DisabledIfSystemProperty(named="chen.wiki.final.http", matches="true")
    void realHttpCompileReviewPublishWithdrawAndSourceRevocationCloseTheLoop() throws Exception {
        JsonNode inspect = data(post("/admin/wiki/sources/inspect", adminToken, Map.of("type", "ARTICLE", "id", "1")));
        JsonNode authorized = data(post("/admin/wiki/sources/authorize", adminToken,
                Map.of("type", "ARTICLE", "id", "1", "fingerprint", inspect.path("fingerprint").asText())));
        String request = "http-" + UUID.randomUUID();
        assertEquals(200, post("/admin/wiki/jobs", adminToken, Map.of("requestId", request,
                "snapshotIds", List.of(authorized.path("snapshotId").asLong()), "topics", List.of())).statusCode());
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!"DRAFT_READY".equals(jdbc.queryForObject("SELECT state FROM wiki_compile_job WHERE request_id=?", String.class, request))) {
            assertTrue(System.nanoTime() < deadline); Thread.sleep(50);
        }
        var draft = get("/admin/wiki/jobs/" + request + "/draft", adminToken);
        assertEquals(200, draft.statusCode()); assertTrue(draft.body().contains("SEMANTIC_NOT_VERIFIED"));
        JsonNode revision = data(post("/admin/wiki/revisions", adminToken, Map.of("requestId", request, "title", "HTTP 中文知识", "summary", "隔离摘要")));
        long page = revision.path("pageId").asLong(), id = revision.path("id").asLong(); String hash = revision.path("bodyHash").asText();
        assertEquals(404, get("/wiki/pages/" + page, null).statusCode());
        assertEquals(200, post("/admin/wiki/revisions/" + id + "/submit", adminToken, Map.of("bodyHash", hash)).statusCode());
        assertEquals(400, post("/admin/wiki/revisions/" + id + "/approve", adminToken,
                Map.of("bodyHash", hash, "confirmEvidence", false, "confirmConflicts", true)).statusCode());
        assertEquals(200, post("/admin/wiki/revisions/" + id + "/approve", adminToken,
                Map.of("bodyHash", hash, "confirmEvidence", true, "confirmConflicts", true)).statusCode());
        assertEquals(200, post("/admin/wiki/revisions/" + id + "/publish", adminToken, Map.of("bodyHash", hash)).statusCode());
        var published = get("/wiki/pages/" + page, null); assertEquals(200, published.statusCode());
        assertFalse(published.body().contains("snapshot=")); assertFalse(published.body().contains("requestId"));
        assertTrue(data(published).path("citations").get(0).path("url").asText().equals("/user/1/article/1"));
        assertEquals(200, post("/admin/wiki/pages/" + page + "/withdraw", adminToken, Map.of("expectedCurrentVersion", 1)).statusCode());
        assertEquals(404, get("/wiki/pages/" + page, null).statusCode());
        assertEquals(200, post("/admin/wiki/pages/" + page + "/restore", adminToken, Map.of("revisionId", id, "expectedCurrentVersion", 1)).statusCode());
        assertEquals(200, post("/fixture/source/withdraw", adminToken, Map.of("articleId", 1)).statusCode());
        assertEquals(404, get("/wiki/pages/" + page, null).statusCode());
        assertEquals(403, post("/admin/wiki/pages/" + page + "/restore", adminToken, Map.of("revisionId", id, "expectedCurrentVersion", 1)).statusCode());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM wiki_compile_attempt WHERE state='SUCCEEDED'", Integer.class));
    }

    @Test @DisabledIfSystemProperty(named="chen.wiki.final.http", matches="true")
    void currentSourceAdmissionAndExactFingerprintAreRecheckedOverHttp() throws Exception {
        JsonNode source = data(post("/admin/wiki/sources/inspect", adminToken, Map.of("type", "ARTICLE", "id", "1")));
        jdbc.update("UPDATE article SET content='<p>新正文</p>' WHERE id=1");
        assertEquals(403, post("/admin/wiki/sources/authorize", adminToken, Map.of("type", "ARTICLE", "id", "1", "fingerprint", source.path("fingerprint").asText())).statusCode());
        jdbc.update("UPDATE article SET visible_range=1 WHERE id=1");
        assertEquals(0, data(get("/admin/wiki/sources", adminToken)).path("total").asInt());
        assertEquals(403, post("/admin/wiki/sources/inspect", adminToken, Map.of("type", "ARTICLE", "id", "1")).statusCode());
    }

    @Test @EnabledIfSystemProperty(named="chen.wiki.final.http", matches="true")
    void realProviderDraftPublishesOverActualSecurityWithoutNewModelCalls() throws Exception {
        String request = finalState.path("requestId").asText();
        int attempts = jdbc.queryForObject("SELECT COUNT(*) FROM wiki_compile_attempt", Integer.class);
        assertEquals("DRAFT_READY", jdbc.queryForObject("SELECT state FROM wiki_compile_job WHERE request_id=?", String.class, request));
        assertEquals(401, get("/admin/wiki/access", null).statusCode());
        assertEquals(403, get("/admin/wiki/access", userToken).statusCode());
        assertEquals(401, get("/admin/wiki/access", "Bearer invalid").statusCode());
        assertEquals(200, get("/admin/wiki/access", adminToken).statusCode());
        assertEquals(401, get("/admin/wiki/jobs/" + request + "/draft", null).statusCode());
        assertEquals(403, get("/admin/wiki/jobs/" + request + "/draft", userToken).statusCode());
        var draft = get("/admin/wiki/jobs/" + request + "/draft", adminToken);
        assertEquals(200, draft.statusCode());
        assertEquals(2, data(draft).path("sources").size());
        assertTrue(data(draft).path("markdown").asText().contains("SEMANTIC_NOT_VERIFIED"));
        var created = post("/admin/wiki/revisions", adminToken, Map.of("requestId", request,
                "title", "真实 Redis Wiki HTTP 验证", "summary", "真实 DeepSeek 双文档草稿的隔离发布验证"));
        assertEquals(200, created.statusCode());
        JsonNode revision = data(created);
        long page = revision.path("pageId").asLong(), id = revision.path("id").asLong();
        Map<String,Object> hash = Map.of("bodyHash", revision.path("bodyHash").asText());
        assertEquals(404, get("/wiki/pages/" + page, null).statusCode());
        assertEquals(200, post("/admin/wiki/revisions/" + id + "/submit", adminToken, hash).statusCode());
        assertEquals(400, post("/admin/wiki/revisions/" + id + "/approve", adminToken, Map.of("bodyHash", hash.get("bodyHash"),
                "confirmEvidence", false, "confirmConflicts", true)).statusCode());
        assertEquals(200, post("/admin/wiki/revisions/" + id + "/approve", adminToken, Map.of("bodyHash", hash.get("bodyHash"),
                "confirmEvidence", true, "confirmConflicts", true)).statusCode());
        assertEquals(200, post("/admin/wiki/revisions/" + id + "/publish", adminToken, hash).statusCode());
        var published = get("/wiki/pages/" + page, null);
        assertEquals(200, published.statusCode());
        assertEquals("no-store", published.headers().firstValue("Cache-Control").orElseThrow());
        assertFalse(published.body().contains("snapshot="));
        assertFalse(published.body().contains("requestId"));
        assertEquals(2, data(published).path("citations").size());
        assertEquals(200, post("/admin/wiki/pages/" + page + "/withdraw", adminToken, Map.of("expectedCurrentVersion", 1)).statusCode());
        assertEquals(404, get("/wiki/pages/" + page, null).statusCode());
        assertEquals(attempts, jdbc.queryForObject("SELECT COUNT(*) FROM wiki_compile_attempt", Integer.class));
        Files.writeString(FINAL_OUT.resolve("http-acceptance.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                "status", "PASS", "requestId", request, "pageId", page, "revisionId", id,
                "boundary", "REAL_PROVIDER_DRAFT_REAL_MYSQL_REAL_SPRING_SECURITY_SYNTHETIC_IDENTITY_LOOKUP",
                "newModelCalls", 0, "draftBodyHash", data(draft).path("bodyHash").asText(),
                "draftStillReadable", get("/admin/wiki/jobs/" + request + "/draft", adminToken).statusCode() == 200)));
    }

    private void attachFinalMysql() throws Exception {
        assertTrue(Set.of("wiki_r2_acceptance", "wiki_final_human").contains(FINAL_DATABASE));
        finalState = JSON.readTree(Files.readString(FINAL_OUT.resolve("runtime/mysql-private.json")));
        String owner = finalState.path("owner").asText(), owned = finalState.path("resource").asText();
        assertTrue(owner.matches("[a-f0-9]{32}"));
        assertEquals("chen-wiki-final-" + owner.substring(0, 12), owned);
        assertEquals(owner, JSON.readTree(docker("inspect", "--format", "{{json .Config.Labels}}", owned)).path("chen.wiki.r2").asText());
        if (!"true".equals(docker("inspect", "--format", "{{.State.Running}}", owned).strip())) docker("start", owned);
        String address = docker("port", owned, "3306/tcp").strip();
        assertTrue(address.matches("127\\.0\\.0\\.1:[0-9]+"));
        String url = "jdbc:mysql://" + address + "/" + FINAL_DATABASE + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=1000&socketTimeout=5000";
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (true) {
            try (var ignored = DriverManager.getConnection(url, "root", finalState.path("password").asText())) { break; }
            catch (java.sql.SQLException unavailable) {
                if (System.nanoTime() >= deadline) throw new IllegalStateException("FINAL_OWNED_MYSQL_TIMEOUT");
                Thread.sleep(250);
            }
        }
        dataSource = new DriverManagerDataSource(url, "root", finalState.path("password").asText());
        jdbc = new JdbcTemplate(dataSource); jdbc.setQueryTimeout(5);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='rag_source_state'", Integer.class) == 0) {
            apply("../sql/g2_rag_catalog.sql");
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name='wiki_article_changed'", Integer.class) == 0) {
            apply("../sql/g3_wiki_invalidation.sql");
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='wiki_page'", Integer.class) == 0) {
            apply("../sql/g3_wiki_product.sql");
        }
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('wiki_page','wiki_revision','wiki_review_event')", Integer.class));
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.triggers WHERE trigger_schema=DATABASE() AND trigger_name IN ('wiki_article_changed','wiki_article_deleted','wiki_help_changed','wiki_help_deleted')", Integer.class));
        assertTrue(finalState.path("requestId").asText().matches("g3-p4-final-real-[a-f0-9]{12}"));
        String currentState = jdbc.queryForObject("SELECT state FROM wiki_compile_job WHERE request_id=?", String.class, finalState.path("requestId").asText());
        if ("wiki_final_human".equals(FINAL_DATABASE)) assertTrue(Set.of("DRAFT_READY", "INVALIDATED").contains(currentState));
        else assertEquals("DRAFT_READY", currentState);
    }

    private void writeFinalRuntime() throws Exception {
        Map<String,Object> runtime = new LinkedHashMap<>();
        runtime.put("apiUrl", baseUrl); runtime.put("adminToken", adminToken); runtime.put("userToken", userToken);
        runtime.put("requestId", finalState.path("requestId").asText()); runtime.put("articleId", 1);
        runtime.put("sourceTitle", jdbc.queryForObject("SELECT title FROM article WHERE id=1", String.class));
        runtime.put("topic", "redis"); runtime.put("withdrawSourceUrl", "/fixture/source/withdraw");
        runtime.put("boundary", "REAL_PROVIDER_DRAFT_REAL_MYSQL_REAL_SPRING_SECURITY_SYNTHETIC_IDENTITY_LOOKUP");
        runtime.put("database", FINAL_DATABASE);
        runtime.put("restoredCopy", "wiki_final_human".equals(FINAL_DATABASE));
        Path pending = FINAL_OUT.resolve("runtime/http-runtime.pending");
        Files.writeString(pending, JSON.writeValueAsString(runtime));
        Files.move(pending, FINAL_OUT.resolve("runtime/http-runtime.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Explicit development server, never a production entry point or a model sender. */
    public static final class FinalServer {
        public static void main(String[] arguments) throws Exception {
            if (!FINAL_HTTP) throw new IllegalStateException("FINAL_HTTP_OPT_IN_REQUIRED");
            temporary = Files.createDirectories(FINAL_OUT.resolve("runtime/http-server"));
            Path stop = FINAL_OUT.resolve("runtime/http-stop.marker"); Files.deleteIfExists(stop);
            WikiProductHttpIntegrationIT fixture = new WikiProductHttpIntegrationIT();
            try {
                fixture.startOwnedMysqlAndActualHttp(); fixture.writeFinalRuntime();
                System.out.println("FINAL_WIKI_HTTP_READY=" + fixture.baseUrl);
                while (!Files.exists(stop)) Thread.sleep(500);
            } finally { fixture.optionalBrowserSessionThenCleanOwnedResources(); }
        }
    }

    @AfterAll void optionalBrowserSessionThenCleanOwnedResources() throws Exception {
        if (FINAL_HTTP) {
            if (tomcat != null) { tomcat.stop(); tomcat.destroy(); }
            if (application != null) application.close();
            return;
        }
        try {
            String browser = System.getProperty("chen.wiki.product.browser");
            if (browser != null && baseUrl != null) {
                reset(); Path directory = Path.of(browser).toAbsolutePath().normalize();
                Path workspace = Path.of("..").toAbsolutePath().normalize();
                assertTrue(directory.startsWith(workspace.resolve(".work/G3_P3_LLM_WIKI_PRODUCT")));
                Files.createDirectories(directory);
                Map<String,Object> runtime = Map.of("apiUrl", baseUrl, "adminToken", adminToken, "userToken", userToken,
                        "articleId", 1, "sourceTitle", SOURCE_TITLE, "topic", "redis", "withdrawSourceUrl", "/fixture/source/withdraw");
                Path pending = directory.resolve("runtime.pending"); Files.writeString(pending, JSON.writeValueAsString(runtime));
                Files.move(pending, directory.resolve("runtime.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                System.out.println("WIKI_BROWSER_READY=" + directory);
                long deadline = System.nanoTime() + Duration.ofMinutes(10).toNanos();
                while (!Files.exists(directory.resolve("stop.marker")) && System.nanoTime() < deadline) Thread.sleep(250);
                assertTrue(Files.exists(directory.resolve("stop.marker")), "BROWSER_SESSION_TIMED_OUT");
            }
        } finally {
            if (tomcat != null) { tomcat.stop(); tomcat.destroy(); }
            if (application != null) application.close();
            if (docker("ps", "-a", "--filter", "label=chen.wiki.product.http=" + identity, "--format", "{{.Names}}").lines().anyMatch(resource::equals)) {
                assertEquals(identity, JSON.readTree(docker("inspect", "--format", "{{json .Config.Labels}}", resource)).get("chen.wiki.product.http").asText());
                docker("rm", "--force", "--volumes", resource);
            }
            if (docker("network", "ls", "--filter", "label=chen.wiki.product.http=" + identity, "--format", "{{.Name}}").lines().anyMatch(resource::equals)) {
                assertEquals(identity, JSON.readTree(docker("network", "inspect", "--format", "{{json .Labels}}", resource)).get("chen.wiki.product.http").asText());
                docker("network", "rm", resource);
            }
            System.out.println("WIKI_HTTP_RESOURCES_REMOVED=" + resource);
        }
    }

    private HttpResponse<String> get(String path, String token) throws Exception { return send("GET", path, token, null); }
    private HttpResponse<String> post(String path, String token, Object body) throws Exception { return send("POST", path, token, body); }
    private HttpResponse<String> send(String method, String path, String token, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(20));
        if (token != null) request.header("Authorization", token);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private static JsonNode data(HttpResponse<String> response) throws Exception { return JSON.readTree(response.body()).path("data"); }
    private void apply(String file) throws Exception {
        String sql = Files.readString(Path.of(file), StandardCharsets.UTF_8).replaceAll("(?m)^--[^\\r\\n]*", "");
        for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
    }
    private static String docker(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker")); command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("OWNED_DOCKER_TIMEOUT"); }
        String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new IllegalStateException("OWNED_DOCKER_FAILED"); return result;
    }

    @Configuration(proxyBeanMethods=false)
    @EnableWebMvc @EnableWebSecurity
    @Import({SecurityConfiguration.class, JwtAuthenticationFilter.class, WikiController.class, WikiAdminController.class, WikiExceptionHandler.class, Support.class})
    static class TestWeb implements WebMvcConfigurer {
        @Override public void configureMessageConverters(List<HttpMessageConverter<?>> converters) { converters.add(new MappingJackson2HttpMessageConverter(JSON)); }
        @Bean AdminSecurity adminSecurity() { return new AdminSecurity(); }
        @Bean JdbcTemplate jdbc() { return new JdbcTemplate(dataSource); }
        @Bean DataSourceTransactionManager manager() { return new DataSourceTransactionManager(dataSource); }
        @Bean(destroyMethod="close") com.zcx.chenstack.rag.CatalogLifecycle catalog() { return new KnowledgeCatalog(List.of()).lifecycle(); }
        @Bean WikiFoundation foundation(JdbcTemplate jdbc, DataSourceTransactionManager manager, com.zcx.chenstack.rag.CatalogLifecycle catalog, AdminSecurity security) {
            return new WikiFoundation(jdbc, manager, new WikiSourceReader(jdbc, catalog, JSON), security);
        }
        @Bean WikiPublicationService publication(JdbcTemplate jdbc, DataSourceTransactionManager manager, WikiFoundation foundation, AdminSecurity security) {
            return new WikiPublicationService(jdbc, manager, foundation, security);
        }
        @Bean @Conditional(DefaultCompiler.class)
        WikiCompilationService compiler(JdbcTemplate jdbc, WikiFoundation foundation) {
            WikiModel model = new WikiModel() {
                public String id() { return "p3-synthetic-mock"; }
                public boolean external() { return false; }
                public Reply generate(String system, String input, int outputTokens) {
                    try {
                        JsonNode data = JSON.readTree(input);
                        if (system.startsWith("WIKI_MAP")) return new Reply(JSON.writeValueAsString(Map.of("facts", List.of(Map.of(
                                "topic", "redis", "key", "缓存", "value", "明确的过期时间", "quote", data.path("text").asText())))), 12L);
                        return new Reply("{\"title\":\"Redis 缓存知识\",\"summary\":[{\"text\":\"缓存采用明确的过期时间。\",\"facts\":[\"f1\"]}],\"sections\":[{\"heading\":\"使用说明\",\"claims\":[{\"text\":\"缓存采用明确的过期时间。\",\"facts\":[\"f1\"]}]}],\"related\":[]}", 12L);
                    } catch (Exception error) { throw new IllegalStateException("SYNTHETIC_MODEL_JSON"); }
                }
            };
            return new WikiCompilationService(foundation, jdbc, JSON, model);
        }
        @Bean WikiManagementService management(JdbcTemplate jdbc, WikiFoundation foundation, WikiPublicationService publication,
                                               org.springframework.beans.factory.ObjectProvider<WikiCompilationService> compiler) {
            return new WikiManagementService(jdbc, foundation, publication, compiler);
        }
        @Bean ChenStackConfig config() { ChenStackConfig config = new ChenStackConfig(); config.setAllowOrigins(List.of("*")); return config; }
        @Bean JwtUtils jwt() { JwtUtils jwt = new JwtUtils(); ReflectionTestUtils.setField(jwt, "secret", JWT_KEY); return jwt; }
        @Bean SysUserMapper users() { SysUserMapper users = mock(SysUserMapper.class); when(users.selectById(1)).thenReturn(user(1)); when(users.selectById(2)).thenReturn(user(2)); return users; }
        @Bean SysUserDetailsService details() { SysUserDetailsService details = mock(SysUserDetailsService.class); when(details.handleLogin(any(SysUser.class))).thenAnswer(call -> new LoginUser(call.getArgument(0))); return details; }
        @Bean SysMenuMapper menus() { return mock(SysMenuMapper.class); }
        @Bean SysRoleMapper roles() { return mock(SysRoleMapper.class); }
        @Bean SysUserRoleMapper userRoles() { return mock(SysUserRoleMapper.class); }
        @Bean SysRoleMenuMapper roleMenus() { return mock(SysRoleMenuMapper.class); }
        @Bean SysRolePermissionMapper rolePermissions() { return mock(SysRolePermissionMapper.class); }
        @Bean SysPermissionMapper permissions() { return mock(SysPermissionMapper.class); }
        @Bean RabbitTemplate rabbit() { return mock(RabbitTemplate.class); }
        @Bean IpUtils ip() { return mock(IpUtils.class); }
        private static SysUser user(int id) {
            SysUser user = new SysUser(); user.setId(id); user.setUsername("p3-fixture-" + id); user.setStatus(StatusEnum.NORMAL.getStatus()); user.setSysPermissions(List.of());
            SysRole role = new SysRole(); role.setRole(id == 1 ? "admin" : "user"); user.setSysRoles(List.of(role)); return user;
        }
    }

    static class DefaultCompiler implements Condition {
        @Override public boolean matches(org.springframework.context.annotation.ConditionContext context,
                                         org.springframework.core.type.AnnotatedTypeMetadata metadata) { return !FINAL_HTTP; }
    }

    @RestController
    static class Support {
        private final JdbcTemplate jdbc;
        private final AdminSecurity security;
        Support(JdbcTemplate jdbc, AdminSecurity security) { this.jdbc = jdbc; this.security = security; }
        @GetMapping({"/user/admin/info", "/user/info"}) Result<Map<String,Object>> info() { return Result.success(Map.of("id", 1, "username", "p3-fixture", "avatar", "")); }
        @GetMapping({"/menu/list", "/message/admin/list"}) Result<List<Object>> list() { return Result.success(List.of()); }
        @GetMapping("/message/admin/count") Result<Integer> count() { return Result.success(0); }
        @PostMapping("/fixture/source/withdraw") Result<Void> withdraw(@RequestBody Map<String,Integer> request) {
            if (!security.isAdmin() || request.getOrDefault("articleId", 0) != 1) throw new WikiProductException(403, "Fixture admin required");
            jdbc.update("UPDATE article SET visible_range=1 WHERE id=1"); return Result.success();
        }
    }
}
