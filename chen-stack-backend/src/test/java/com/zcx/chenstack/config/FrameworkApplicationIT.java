package com.zcx.chenstack.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in; runs the packaged Main against cached, exclusively owned infrastructure. */
class FrameworkApplicationIT {
    @TempDir(cleanup = org.junit.jupiter.api.io.CleanupMode.ON_SUCCESS) Path temporary;

    @Test void packagedMainStartsWithMysqlRedisRabbitMinioWikiAndMockSseWithoutExternalProviderAccess() throws Exception {
        assertEquals("true", System.getProperty("chen.framework.it"), "Explicit isolated integration opt-in required");
        Path jar = Path.of(System.getProperty("chen.framework.jar")).toAbsolutePath();
        assertTrue(Files.isRegularFile(jar));
        String owner = UUID.randomUUID().toString();
        String network = "chen-framework-it-" + owner;
        String password = UUID.randomUUID().toString().replace("-", "");
        List<String> containers = new ArrayList<>();
        Process application = null;
        HttpServer provider = null;
        AtomicInteger providerCalls = new AtomicInteger();
        AtomicInteger providerStatus = new AtomicInteger(200);
        boolean networkCreated = false;
        try {
            docker("network", "create", "--driver", "bridge", "--label", "chen.framework.it=" + owner, network);
            networkCreated = true;
            int mysql = start(containers, network, owner, "mysql", "mysql:8.0", 3306,
                    "MYSQL_ROOT_PASSWORD=" + password + "\nMYSQL_DATABASE=chen_framework_it\nMYSQL_ROOT_HOST=%\n", "/var/lib/mysql");
            int redis = start(containers, network, owner, "redis", "redis:7-alpine", 6379, "", "/data");
            int rabbit = start(containers, network, owner, "rabbit", "rabbitmq:3-management-alpine", 5672,
                    "RABBITMQ_DEFAULT_USER=fixture\nRABBITMQ_DEFAULT_PASS=" + password
                            + "\nRABBITMQ_SERVER_ADDITIONAL_ERL_ARGS=+S 2:2 +SDcpu 1 +SDio 1\n", "/var/lib/rabbitmq");
            int minio = start(containers, network, owner, "minio", "minio/minio:RELEASE.2025-04-08T15-41-24Z", 9000,
                    "MINIO_ROOT_USER=fixture\nMINIO_ROOT_PASSWORD=" + password + "\n", "/data", "server", "/data");
            String jdbcUrl = "jdbc:mysql://127.0.0.1:" + mysql
                    + "/chen_framework_it?allowPublicKeyRetrieval=true&useSSL=false&connectTimeout=1000&socketTimeout=3000";
            long ready = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            while (true) {
                try (var connection = DriverManager.getConnection(jdbcUrl, "root", password)) {
                    applyBusinessDdlOnly(connection);
                    List<String> migrations = new ArrayList<>(List.of("../sql/g2_rag_catalog.sql", "../sql/g3_wiki_foundation.sql"));
                    if (Boolean.getBoolean("chen.framework.compose-modes")) {
                        migrations.addAll(List.of("../sql/g3_wiki_compiler.sql", "../sql/g3_wiki_invalidation.sql", "../sql/g3_wiki_product.sql"));
                    } else if (Boolean.getBoolean("chen.framework.wiki-provider")) {
                        migrations.add("../sql/g3_wiki_compiler.sql");
                    }
                    for (String file : migrations) {
                        String sql = Files.readString(Path.of(file)).replaceAll("(?m)^\\s*--.*$", "");
                        for (String statement : sql.split(";")) if (!statement.isBlank()) {
                            try (var command = connection.createStatement()) { command.execute(statement); }
                        }
                    }
                    try (var command = connection.createStatement()) {
                        command.executeUpdate("INSERT INTO sys_user(id,username,password,status,create_time,update_time) VALUES(7,'fixture','fixture',0,NOW(),NOW())");
                        command.executeUpdate("INSERT INTO sys_role(id,role,name,status,create_time,update_time) VALUES(2,'user','fixture',0,NOW(),NOW())");
                        command.executeUpdate("INSERT INTO sys_user_role(user_id,role_id) VALUES(7,2)");
                        if (Boolean.getBoolean("chen.framework.compose-modes")) {
                            command.executeUpdate("INSERT INTO sys_user(id,username,password,status,create_time,update_time) VALUES(8,'fixture-admin','fixture',0,NOW(),NOW())");
                            command.executeUpdate("INSERT INTO sys_role(id,role,name,status,create_time,update_time) VALUES(3,'admin','fixture',0,NOW(),NOW())");
                            command.executeUpdate("INSERT INTO sys_user_role(user_id,role_id) VALUES(8,3)");
                            command.executeUpdate("INSERT INTO sys_menu(id,name,sort,status,create_time,update_time) VALUES(1,'fixture',1,0,NOW(),NOW())");
                            command.executeUpdate("INSERT INTO sys_role_menu(role_id,menu_id) VALUES(3,1)");
                            command.executeUpdate("INSERT INTO sys_permission(id,description,permission,menu_id,create_time,update_time) VALUES(1,'fixture','fixture:mode',1,NOW(),NOW())");
                            command.executeUpdate("INSERT INTO sys_role_permission(role_id,permission_id) VALUES(3,1)");
                        }
                    }
                    break;
                } catch (java.sql.SQLException unavailable) {
                    if (unavailable.getSQLState() != null && !unavailable.getSQLState().startsWith("08")) {
                        throw new AssertionError("Owned schema/fixture setup failed", unavailable);
                    }
                    if (System.nanoTime() >= ready) throw new AssertionError("Owned MySQL did not become ready", unavailable);
                    Thread.sleep(500);
                }
            }
            var rabbitClient = new com.rabbitmq.client.ConnectionFactory();
            rabbitClient.setHost("127.0.0.1"); rabbitClient.setPort(rabbit);
            rabbitClient.setUsername("fixture"); rabbitClient.setPassword(password);
            rabbitClient.setConnectionTimeout(1000); rabbitClient.setHandshakeTimeout(2000);
            rabbitClient.setAutomaticRecoveryEnabled(false);
            long rabbitReady = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (true) {
                try (var connection = rabbitClient.newConnection("framework-it-readiness")) { break; }
                catch (java.io.IOException | java.util.concurrent.TimeoutException unavailable) {
                    if (System.nanoTime() >= rabbitReady) {
                        throw new AssertionError("Owned RabbitMQ did not become ready:\n" + docker("logs", "--tail", "35", containers.get(2)));
                    }
                    Thread.sleep(500);
                }
            }
            provider = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            provider.createContext("/chat/completions", exchange -> {
                providerCalls.incrementAndGet();
                var wire = new ObjectMapper().readTree(exchange.getRequestBody().readAllBytes());
                boolean scope = wire.path("messages").get(0).path("content").asText().contains("范围分类器");
                String content = scope ? "USER_HELP" : "你好，这是隔离升级验证回复。";
                String body = providerStatus.get() == 200
                        ? "data: {\"id\":\"framework\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"fixture\","
                        + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"" + content + "\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                        : "{\"error\":{\"message\":\"synthetic unavailable\"}}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", providerStatus.get() == 200 ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(providerStatus.get(), bytes.length);
                exchange.getResponseBody().write(bytes); exchange.close();
            });
            provider.start();
            Path ledger = temporary.resolve("provider.ledger");
            Files.writeString(ledger, "CHEN_RELEASE_HTTP_BUDGET_V1|framework-it|64|20\n");
            Path log = temporary.resolve("application.log");
            List<String> command = new ArrayList<>(List.of(
                    Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(), "-Dfile.encoding=UTF-8",
                    "-Djava.io.tmpdir=" + temporary, "-jar", jar.toString(),
                    "--spring.config.location=" + Path.of("src/test/resources/framework-upgrade-it.yaml").toAbsolutePath().toUri(),
                    "--logging.config=" + Path.of("src/test/resources/framework-upgrade-logback.xml").toAbsolutePath().toUri(),
                    "--spring.datasource.url=" + jdbcUrl, "--spring.datasource.password=" + password,
                    "--spring.data.redis.port=" + redis, "--spring.rabbitmq.port=" + rabbit,
                    "--spring.rabbitmq.password=" + password, "--minio.endpoint=http://127.0.0.1:" + minio,
                    "--minio.secretKey=" + password,
                    "--spring.ai.openai.base-url=http://127.0.0.1:" + provider.getAddress().getPort(),
                    "--chen.rag.release.budget-ledger-path=" + ledger, "--chen.rag.release.budget-id=framework-it"));
            if (Boolean.getBoolean("chen.framework.wiki-provider")) {
                command.addAll(List.of("--chen.wiki.compiler.enabled=true", "--chen.wiki.compiler.provider=deepseek",
                        "--spring.ai.openai.chat.options.model=deepseek-flash"));
            }
            ProcessBuilder builder = new ProcessBuilder(command).directory(temporary.toFile())
                    .redirectErrorStream(true).redirectOutput(log.toFile());
            // 子进程不继承用户的 .env、API Key 或线上连接变量。
            builder.environment().clear();
            builder.environment().put("SystemRoot", System.getenv("SystemRoot"));
            builder.environment().put("TEMP", temporary.toString());
            builder.environment().put("TMP", temporary.toString());
            application = builder.start();
            long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
            String output;
            while (true) {
                output = Files.exists(log) ? Files.readString(log) : "";
                if (output.contains("核心服务连接正常，系统可启动")) break;
                if (!application.isAlive() || System.nanoTime() >= deadline) {
                    throw new AssertionError("Candidate Main did not start:\n" + output.substring(Math.max(0, output.length() - 8000)));
                }
                Thread.sleep(250);
            }
            assertTrue(output.contains("RabbitMQ 连接正常"));
            assertTrue(output.contains("MinIO 连接正常"));
            var port = Pattern.compile("Tomcat started on port (\\d+)").matcher(output);
            assertTrue(port.find(), "A real embedded Tomcat must bind a loopback port");
            String base = "http://127.0.0.1:" + port.group(1);
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            var health = http.send(HttpRequest.newBuilder(URI.create(base + "/actuator/health"))
                    .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, health.statusCode(), health.body());
            assertTrue(health.body().contains("\"status\":\"UP\""));
            System.out.println("FRAMEWORK_IT_HEALTH_HTTP=" + health.statusCode() + " " + health.body());
            var anonymous = http.send(HttpRequest.newBuilder(URI.create(base + "/ai/customer-service/status"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, anonymous.statusCode());
            var jwt = new com.zcx.chenstack.utils.JwtUtils();
            org.springframework.test.util.ReflectionTestUtils.setField(jwt, "secret", "framework-it-synthetic-secret");
            String token = "Bearer " + jwt.createToken(7);
            var success = sse(http, base, token, "success");
            System.out.println("FRAMEWORK_IT_SSE_SUCCESS=" + success);
            assertTrue(success.stream().anyMatch(event -> event.path("type").asText().equals("TEXT")
                    && event.path("text").asText().contains("隔离升级验证回复")), success.toString());
            assertTrue(success.stream().noneMatch(event -> event.path("type").asText().equals("ERROR_STATE")));
            assertEquals("DONE", success.getLast().path("type").asText());
            assertEquals(1, success.stream().filter(event -> event.path("type").asText().equals("DONE")).count());
            assertEquals(2, providerCalls.get());
            providerStatus.set(503);
            var failure = sse(http, base, token, "failure");
            System.out.println("FRAMEWORK_IT_SSE_FAILURE=" + failure);
            assertEquals("ERROR_STATE", failure.getFirst().path("type").asText());
            assertEquals("MODEL_SERVICE_ERROR", failure.getFirst().path("errorCode").asText());
            assertEquals("DONE", failure.getLast().path("type").asText());
            assertTrue(failure.stream().noneMatch(event -> event.path("type").asText().equals("TEXT")));
            assertEquals(3, providerCalls.get());
            assertEquals(3L, new com.zcx.chenstack.rag.ReleaseProviderBudget(ledger.toString(), "framework-it", 64, 20).snapshot().get("chat"));
            if (Boolean.getBoolean("chen.framework.wiki-provider")) {
                assertTrue(output.contains("Wiki Provider已装配：deepseek"));
                try (var connection = DriverManager.getConnection(jdbcUrl, "root", password);
                     var statement = connection.createStatement();
                     var rows = statement.executeQuery("SELECT COUNT(*) FROM wiki_compile_attempt")) {
                    assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
                }
                System.out.println("FRAMEWORK_IT_WIKI_PROVIDER=DEEPSEEK_CONFIGURED_DEFAULT_DENY;WIKI_SENDS=0");
            }
            output.lines().filter(line -> line.contains("连接正常") || line.contains("Tomcat started") || line.contains("Started Main"))
                    .forEach(line -> System.out.println("FRAMEWORK_IT_STARTUP=" + line));
            System.out.println("FRAMEWORK_IT_PACKAGED_MAIN=STARTED;MYSQL_REDIS_RABBIT_MINIO=CONNECTED;HEALTH=UP;ANONYMOUS_AI=401;WIKI_ENABLED=true;SSE_SUCCESS_AND_ERROR=PASS;MOCK_CHAT_HTTP=3;EXTERNAL_PROVIDER_CALLS=0");
            if (Boolean.getBoolean("chen.framework.compose-modes")) {
                application.destroy();
                if (!application.waitFor(15, TimeUnit.SECONDS)) application.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
                application = null;
                composeModes(jar, network, owner, containers, password, jdbcUrl, jwt, http);
            }
        } finally {
            if (application != null) {
                application.destroy();
                if (!application.waitFor(15, TimeUnit.SECONDS)) application.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
            if (provider != null) provider.stop(0);
            for (String container : containers.reversed()) {
                assertEquals(owner, new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                        docker("inspect", "--format", "{{json .Config.Labels}}", container)).path("chen.framework.it").asText());
                docker("rm", "-f", "-v", container);
            }
            if (networkCreated) {
                assertEquals(owner, new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                        docker("network", "inspect", "--format", "{{json .Labels}}", network)).path("chen.framework.it").asText());
                docker("network", "rm", network);
            }
            System.out.println("FRAMEWORK_IT_OWNED_RESOURCES_REMOVED=true");
        }
    }

    private void composeModes(Path jar, String network, String owner, List<String> containers, String password,
                              String jdbcUrl, com.zcx.chenstack.utils.JwtUtils jwt, HttpClient http) throws Exception {
        String image = System.getProperty("chen.framework.runtime-image", "chen-stack-backend");
        docker("image", "inspect", "--format", "{{.Id}}", image);
        String mock = network + "-provider";
        Path classes = Path.of(FrameworkApplicationIT.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        docker("run", "--pull=never", "-d", "--name", mock, "--network", network, "--dns", "127.0.0.1",
                "--label", "chen.framework.it=" + owner, "--tmpfs", "/tmp",
                "--mount", "type=bind,source=" + classes + ",target=/tests,readonly",
                "--mount", "type=bind,source=" + jar + ",target=/app/candidate.jar,readonly",
                "--mount", "type=bind,source=" + temporary + ",target=/evidence",
                "-p", "127.0.0.1::5000", "-p", "127.0.0.1::6333", "-p", "127.0.0.1::6334",
                "--entrypoint", "java", image, "-Dloader.path=/tests", "-Dloader.main=" + ComposeMockProvider.class.getName(),
                "-cp", "/app/candidate.jar", "org.springframework.boot.loader.launch.PropertiesLauncher");
        containers.add(mock);
        String qdrant = network + "-qdrant";
        docker("run", "--pull=never", "-d", "--name", qdrant, "--network", "container:" + mock,
                "--label", "chen.framework.it=" + owner, "--tmpfs", "/qdrant/storage",
                "--env", "QDRANT__TELEMETRY_DISABLED=true", "qdrant/qdrant:v1.13.6");
        containers.add(qdrant);
        String qdrantBase = "http://" + docker("port", mock, "6333/tcp");
        long qdrantReady = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (true) {
            try {
                if (http.send(HttpRequest.newBuilder(URI.create(qdrantBase + "/collections")).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).statusCode() == 200) break;
            } catch (java.io.IOException notReady) { /* Dedicated Qdrant is still starting. */ }
            assertTrue(System.nanoTime() < qdrantReady, "Owned Qdrant did not become ready"); Thread.sleep(250);
        }
        String preparation = docker("exec", mock, "java", "-Dloader.path=/tests", "-Dloader.main=" + ModeFixturePreparer.class.getName(),
                "-cp", "/app/candidate.jar", "org.springframework.boot.loader.launch.PropertiesLauncher", containers.getFirst());
        Files.writeString(temporary.resolve("mode-preparation.log"), preparation);
        String published = docker("port", mock, "5000/tcp");
        assertTrue(published.startsWith("127.0.0.1:"));
        String base = "http://" + published;
        Path compose = temporary.resolve("modes.compose.yml");
        Path environment = temporary.resolve("modes.env");
        String backend = network + "-backend";
        String project = "chen-framework-modes-" + owner;
        List<String> options = List.of("--spring.config.location=file:/fixture/application.yaml",
                "--logging.config=file:/fixture/logback.xml", "--server.address=0.0.0.0", "--server.port=5000",
                "--spring.datasource.url=jdbc:mysql://" + containers.get(0) + ":3306/chen_framework_it?allowPublicKeyRetrieval=true&useSSL=false&connectTimeout=1000&socketTimeout=3000",
                "--spring.datasource.password=" + password, "--spring.data.redis.host=" + containers.get(1),
                "--spring.data.redis.port=6379", "--spring.rabbitmq.host=" + containers.get(2),
                "--spring.rabbitmq.port=5672", "--spring.rabbitmq.password=" + password,
                "--minio.endpoint=http://" + containers.get(3) + ":9000", "--minio.secretKey=" + password,
                "--spring.ai.openai.base-url=http://127.0.0.1:18080", "--chen.wiki.product.enabled=true",
                "--chen.rag.enabled=true", "--chen.rag.embedding-api-key=fixture-only",
                "--chen.rag.embedding-base-url=http://127.0.0.1:18080", "--chen.rag.embedding-model=framework-vector-1024",
                "--chen.rag.qdrant-grpc-port=6334", "--chen.rag.collection=chen_framework_modes",
                "--chen.rag.release.recovery-enabled=true", "--chen.rag.release.manifest-path=/evidence/rag-manifest.json",
                "--chen.rag.release.operator=framework-it", "--chen.rag.release.environment=framework-it",
                "--chen.rag.release.artifact-path=/app/candidate.jar",
                "--management.endpoint.health.show-components=always",
                "--chen.rag.release.budget-ledger-path=/evidence/provider.ledger", "--chen.rag.release.budget-id=framework-it");
        ObjectMapper mapper = new ObjectMapper();
        Files.writeString(compose, """
                services:
                  backend:
                    image: %s
                    pull_policy: never
                    container_name: %s
                    restart: "no"
                    network_mode: "container:%s"
                    labels:
                      chen.framework.it: "%s"
                    environment:
                      AI_KNOWLEDGE_MODE: "${AI_KNOWLEDGE_MODE:?Select a fixed test mode}"
                    entrypoint: ["java", "-Dfile.encoding=UTF-8", "-Djava.io.tmpdir=/tmp", "-jar", "/app/candidate.jar"]
                    command: %s
                    tmpfs: ["/tmp"]
                    volumes:
                      - type: bind
                        source: %s
                        target: /app/candidate.jar
                        read_only: true
                      - type: bind
                        source: %s
                        target: /fixture/application.yaml
                        read_only: true
                      - type: bind
                        source: %s
                        target: /fixture/logback.xml
                        read_only: true
                      - type: bind
                        source: %s
                        target: /evidence
                """.formatted(mapper.writeValueAsString(image), backend, mock, owner, mapper.writeValueAsString(options),
                mapper.writeValueAsString(jar.toString()),
                mapper.writeValueAsString(Path.of("src/test/resources/framework-upgrade-it.yaml").toAbsolutePath().toString()),
                mapper.writeValueAsString(Path.of("src/test/resources/framework-upgrade-logback.xml").toAbsolutePath().toString()),
                mapper.writeValueAsString(temporary.toString())));
        List<String> composeCommand = List.of("compose", "--project-name", project, "--project-directory", temporary.toString(),
                "--env-file", environment.toString(), "-f", compose.toString());
        String previousId = null;
        var wikiFixture = mapper.readTree(Files.readString(temporary.resolve("wiki-fixture.json")));
        var ragFixture = mapper.readTree(Files.readString(temporary.resolve("rag-fixture.json")));
        List<Long> before = knowledgeCounts(jdbcUrl, password);
        try {
            for (String mode : List.of("chat", "rag", "wiki")) {
                Files.writeString(environment, "AI_KNOWLEDGE_MODE=" + mode + "\n");
                compose(composeCommand, "up", "-d", "--no-deps", "--force-recreate", "--no-build", "--pull", "never", "backend");
                String id = docker("inspect", "--format", "{{.Id}}", backend);
                assertNotEquals(previousId, id, "Changing .env must replace the backend container");
                previousId = id;
                assertEquals(owner, mapper.readTree(docker("inspect", "--format", "{{json .Config.Labels}}", backend))
                        .path("chen.framework.it").asText());
                assertTrue(mapper.readTree(docker("inspect", "--format", "{{json .Config.Env}}", backend)).toString()
                        .contains("\"AI_KNOWLEDGE_MODE=" + mode + "\""));
                long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
                String output;
                while (true) {
                    output = docker("logs", backend);
                    // Main prints this only after all ApplicationRunners and readiness publication finish.
                    if (output.contains("项目启动成功!")) break;
                    if (!"true".equals(docker("inspect", "--format", "{{.State.Running}}", backend)) || System.nanoTime() >= deadline) {
                        throw new AssertionError("Owned Compose backend failed to start for " + mode + ":\n"
                                + output.substring(Math.max(0, output.length() - 8000)));
                    }
                    Thread.sleep(1000);
                }
                Files.writeString(temporary.resolve("compose-" + mode + ".log"), output);
                var health = http.send(HttpRequest.newBuilder(URI.create(base + "/actuator/health"))
                        .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
                Files.writeString(temporary.resolve("compose-" + mode + "-health.json"), health.body());
                assertEquals(200, health.statusCode(), health.body());
                assertTrue(health.body().contains("\"status\":\"UP\""));
                var diagnostic = http.send(HttpRequest.newBuilder(URI.create(base + "/admin/ai/knowledge-mode"))
                        .header("Origin", "http://localhost").header("Authorization", "Bearer " + jwt.createToken(8)).timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(200, diagnostic.statusCode(), diagnostic.body());
                assertEquals(mode, mapper.readTree(diagnostic.body()).path("data").path("activeMode").asText());
                assertEquals("no-store", diagnostic.headers().firstValue("Cache-Control").orElse(""));
                var denied = http.send(HttpRequest.newBuilder(URI.create(base + "/admin/ai/knowledge-mode"))
                        .header("Origin", "http://localhost").header("Authorization", "Bearer " + jwt.createToken(7)).timeout(Duration.ofSeconds(10)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(403, denied.statusCode());
                var publicWiki = http.send(HttpRequest.newBuilder(URI.create(base + "/wiki/pages/" + wikiFixture.path("pageId").asLong()))
                        .header("Origin", "http://localhost").timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, publicWiki.statusCode(), publicWiki.body());
                assertTrue(publicWiki.body().contains("FRAMEWORK_PUBLISHED_WIKI_SENTINEL"));
                var privateWiki = http.send(HttpRequest.newBuilder(URI.create(base + "/wiki/pages/" + wikiFixture.path("privatePageId").asLong()))
                        .header("Origin", "http://localhost").timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(404, privateWiki.statusCode(), privateWiki.body());
                Path calls = temporary.resolve("compose-provider-calls");
                long sentBefore = Files.exists(calls) ? Files.size(calls) : 0;
                Path embeddingCalls = temporary.resolve("compose-embedding-calls");
                long embeddingBefore = Files.exists(embeddingCalls) ? Files.size(embeddingCalls) : 0;
                var events = sse(http, base, "Bearer " + jwt.createToken(7), "compose-" + mode,
                        "rag".equals(mode) ? "用户名或邮箱登录需要什么？" : "Redis 过期时间是多少？");
                assertEquals("DONE", events.getLast().path("type").asText());
                assertTrue(events.stream().noneMatch(event -> "ERROR_STATE".equals(event.path("type").asText())), events.toString());
                String text = events.stream().filter(event -> "TEXT".equals(event.path("type").asText()))
                        .map(event -> event.path("text").asText()).collect(java.util.stream.Collectors.joining());
                assertTrue(text.contains(switch (mode) {
                    case "wiki" -> "FRAMEWORK_PUBLISHED_WIKI_SENTINEL";
                    case "rag" -> ragFixture.path("quote").asText();
                    default -> "隔离升级验证回复";
                }), text);
                if ("rag".equals(mode)) assertTrue(text.contains(ragFixture.path("title").asText()), text);
                long embedded = (Files.exists(embeddingCalls) ? Files.size(embeddingCalls) : 0) - embeddingBefore;
                assertEquals("rag".equals(mode) ? 1 : 0, embedded, "Only RAG queries may call the isolated embedding provider");
                long sent = Files.size(calls) - sentBefore;
                assertEquals("chat".equals(mode) ? 2 : 3, sent, "Classifier and bounded real tool round must finish");
                assertEquals(before, knowledgeCounts(jdbcUrl, password), "Mode switching must not rebuild or publish knowledge");
                System.out.println("FRAMEWORK_COMPOSE_MODE=" + mode + ";CONTAINER_RECREATED=true;ENV_APPLIED=true;ADMIN_DIAGNOSTIC=PASS;USER_DIAGNOSTIC=403;SSE=PASS;KNOWLEDGE_COUNTS_UNCHANGED=true;MOCK_CHAT_HTTP=" + sent + ";MOCK_EMBEDDING_HTTP=" + embedded);
                if ("wiki".equals(mode)) {
                    try (var connection = DriverManager.getConnection(jdbcUrl, "root", password);
                         var statement = connection.prepareStatement("UPDATE article SET visible_range=1 WHERE id=?")) {
                        statement.setLong(1, wikiFixture.path("articleId").asLong()); assertEquals(1, statement.executeUpdate());
                    }
                    var revoked = sse(http, base, "Bearer " + jwt.createToken(7), "compose-wiki-revoked", "Redis 过期时间是多少？");
                    String refused = revoked.stream().filter(event -> "TEXT".equals(event.path("type").asText()))
                            .map(event -> event.path("text").asText()).collect(java.util.stream.Collectors.joining());
                    assertTrue(refused.contains("无法依据 Wiki 回答"), revoked.toString());
                    assertFalse(refused.contains("FRAMEWORK_PUBLISHED_WIKI_SENTINEL"));
                    assertEquals("DONE", revoked.getLast().path("type").asText());
                    assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(base + "/wiki/pages/" + wikiFixture.path("pageId").asLong()))
                            .header("Origin", "http://localhost").timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                    var restore = http.send(HttpRequest.newBuilder(URI.create(base + "/admin/wiki/pages/" + wikiFixture.path("pageId").asLong() + "/restore"))
                            .header("Origin", "http://localhost").header("Authorization", "Bearer " + jwt.createToken(8))
                            .header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))
                            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(Map.of("revisionId", wikiFixture.path("revisionId").asLong(), "expectedCurrentVersion", 1)))).build(),
                            HttpResponse.BodyHandlers.ofString());
                    assertEquals(403, restore.statusCode());
                    assertEquals(before, knowledgeCounts(jdbcUrl, password));
                    System.out.println("FRAMEWORK_COMPOSE_WIKI=REAL_PUBLISHED_CONTEXT;SOURCE_REVOCATION=DENIED;OLD_VERSION_RESTORE=403;PRIVATE_DRAFT=404");
                }
            }
        } finally {
            String exists = docker("ps", "-aq", "--filter", "name=^/" + backend + "$");
            if (!exists.isBlank()) {
                assertEquals(owner, mapper.readTree(docker("inspect", "--format", "{{json .Config.Labels}}", backend))
                        .path("chen.framework.it").asText());
                Files.writeString(temporary.resolve("compose-final.log"), docker("logs", backend));
            }
            Files.writeString(temporary.resolve("compose-provider-final.log"), docker("logs", mock));
            Files.writeString(temporary.resolve("compose-qdrant-final.log"), docker("logs", qdrant));
            System.out.println("FRAMEWORK_IT_DIAGNOSTICS=" + temporary);
            compose(composeCommand, "down", "--remove-orphans", "--volumes", "--timeout", "5");
        }
    }

    private List<Long> knowledgeCounts(String jdbcUrl, String password) throws Exception {
        List<Long> counts = new ArrayList<>();
        try (var connection = DriverManager.getConnection(jdbcUrl, "root", password); var command = connection.createStatement()) {
            for (String table : List.of("rag_source_state", "wiki_compile_job", "wiki_page", "wiki_revision")) {
                try (var rows = command.executeQuery("SELECT COUNT(*) FROM " + table)) { assertTrue(rows.next()); counts.add(rows.getLong(1)); }
            }
        }
        return counts;
    }

    private String compose(List<String> command, String... arguments) throws Exception {
        List<String> complete = new ArrayList<>(command); complete.addAll(List.of(arguments));
        return docker(complete.toArray(String[]::new));
    }

    /** The backend shares this container's namespace, so Mock HTTP never leaves loopback. */
    public static final class ComposeMockProvider {
        public static void main(String[] arguments) throws Exception {
            ObjectMapper mapper = new ObjectMapper();
            AtomicInteger embeddings = new AtomicInteger();
            HttpServer server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 18080), 0);
            server.createContext("/ready", exchange -> { exchange.sendResponseHeaders(204, -1); exchange.close(); });
            server.createContext("/embeddings", exchange -> {
                var wire = mapper.readTree(exchange.getRequestBody().readNBytes(100_001));
                int count = wire.path("input").isArray() ? wire.path("input").size() : 1;
                if (embeddings.incrementAndGet() > 64 || count < 1 || count > 10) {
                    exchange.sendResponseHeaders(429, -1); exchange.close(); return;
                }
                List<Object> data = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    float[] vector = new float[1024]; vector[0] = 1;
                    data.add(Map.of("object", "embedding", "embedding", vector, "index", i));
                }
                byte[] body = mapper.writeValueAsBytes(Map.of("object", "list", "model", "framework-vector-1024", "data", data,
                        "usage", Map.of("prompt_tokens", count, "total_tokens", count)));
                Files.writeString(Path.of("/evidence/compose-embedding-calls"), "x", java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
            server.createContext("/chat/completions", exchange -> {
                var request = mapper.readTree(exchange.getRequestBody().readNBytes(100_001));
                Files.writeString(Path.of("/evidence/compose-provider-calls"), "x", java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                Set<String> tools = new java.util.HashSet<>();
                request.path("tools").forEach(tool -> tools.add(tool.path("function").path("name").asText()));
                String messages = request.path("messages").toString();
                String toolText = "";
                for (var message : request.path("messages")) if ("tool".equals(message.path("role").asText())) toolText += message.path("content").asText();
                Map<String,Object> delta = new java.util.LinkedHashMap<>(); delta.put("role", "assistant");
                String finish = "stop", evidence;
                if (messages.contains("范围分类器")) {
                    delta.put("content", "USER_HELP"); evidence = "SCOPE";
                } else if (messages.contains("当前 AI_KNOWLEDGE_MODE=chat")) {
                    if (tools.stream().anyMatch(name -> Set.of("searchPublishedWiki", "readPublishedWiki", "searchPlatformHelp", "readPlatformHelp").contains(name))) {
                        throw new IllegalStateException("CHAT_EXPOSED_KNOWLEDGE_TOOLS");
                    }
                    delta.put("content", "你好，这是隔离升级验证回复。"); evidence = "CHAT_NO_KNOWLEDGE_TOOLS";
                } else if (tools.contains("searchPublishedWiki")) {
                    if (tools.contains("searchPlatformHelp") || messages.contains("FRAMEWORK_DRAFT_PRIVATE_SENTINEL")) throw new IllegalStateException("WIKI_SCOPE_LEAK");
                    if (toolText.isEmpty()) {
                        delta.put("tool_calls", tool("searchPublishedWiki", "Redis", mapper)); finish = "tool_calls"; evidence = "WIKI_TOOL_CALL";
                    } else if (toolText.contains("FRAMEWORK_PUBLISHED_WIKI_SENTINEL") && toolText.contains("Framework Redis 合法来源")) {
                        delta.put("content", "FRAMEWORK_PUBLISHED_WIKI_SENTINEL：Redis TTL 为 60 seconds。来源：《Framework Redis 合法来源》。"); evidence = "WIKI_GROUNDED";
                    } else {
                        delta.put("content", "本轮未取得有效 Wiki 来源，不能确认。"); evidence = "WIKI_NO_EVIDENCE";
                    }
                } else if (tools.contains("searchPlatformHelp")) {
                    if (tools.contains("searchPublishedWiki") || messages.contains("FRAMEWORK_PUBLISHED_WIKI_SENTINEL")) throw new IllegalStateException("RAG_SCOPE_LEAK");
                    if (toolText.isEmpty()) {
                        String title = mapper.readTree(Files.readString(Path.of("/evidence/rag-fixture.json"))).path("title").asText();
                        delta.put("tool_calls", tool("searchPlatformHelp", title, mapper)); finish = "tool_calls"; evidence = "RAG_TOOL_CALL";
                    } else {
                        var expected = mapper.readTree(Files.readString(Path.of("/evidence/rag-fixture.json")));
                        if (!toolText.contains(expected.path("title").asText()) || !toolText.contains(expected.path("quote").asText())) throw new IllegalStateException("RAG_CONTEXT_MISSING_SOURCE");
                        delta.put("content", "依据《" + expected.path("title").asText() + "》：" + expected.path("quote").asText()); evidence = "RAG_GROUNDED";
                    }
                } else throw new IllegalStateException("EXPECTED_MODE_TOOLS_MISSING");
                Files.writeString(Path.of("/evidence/compose-mode-evidence.log"), evidence + "\n",
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                String body = "data: " + mapper.writeValueAsString(Map.of("id", "compose", "object", "chat.completion.chunk", "created", 1,
                        "model", "fixture", "choices", List.of(Map.of("index", 0, "delta", delta, "finish_reason", finish)))) + "\n\ndata: [DONE]\n\n";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
            });
            server.start();
            Thread.currentThread().join();
        }

        private static List<Object> tool(String name, String query, ObjectMapper mapper) throws java.io.IOException {
            return List.of(Map.of("index", 0, "id", "framework-search", "type", "function",
                    "function", Map.of("name", name, "arguments", mapper.writeValueAsString(Map.of("query", query)))));
        }
    }

    /** Runs beside Main with identical endpoint identity; uses only the owned database/index and fixture HTTP. */
    public static final class ModeFixturePreparer {
        public static void main(String[] arguments) throws Exception {
            ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
            String password = Files.readAllLines(Path.of("/evidence/mysql.env")).stream()
                    .filter(line -> line.startsWith("MYSQL_ROOT_PASSWORD=")).findFirst().orElseThrow().substring("MYSQL_ROOT_PASSWORD=".length());
            var source = new org.springframework.jdbc.datasource.DriverManagerDataSource("jdbc:mysql://" + arguments[0]
                    + ":3306/chen_framework_it?allowPublicKeyRetrieval=true&useSSL=false&connectTimeout=1000&socketTimeout=3000", "root", password);
            var jdbc = new org.springframework.jdbc.core.JdbcTemplate(source);
            var build = com.zcx.chenstack.rag.PublicationBuildIdentity.read(Path.of("/app/candidate.jar"));
            var drafts = List.of(mapper.readValue(build.resources().get(com.zcx.chenstack.rag.PublicationManifest.PLATFORM_HELP_RESOURCE),
                    com.zcx.chenstack.rag.KnowledgeDocument[].class));
            com.zcx.chenstack.rag.JdbcCatalogStore.initialize(source, drafts);
            var lifecycle = com.zcx.chenstack.rag.KnowledgeCatalog.restore(com.zcx.chenstack.rag.JdbcCatalogStore.open(source)).lifecycle();
            RagProperties properties = new RagProperties(); properties.setEnabled(true); properties.setCollection("chen_framework_modes");
            properties.setEmbeddingBaseUrl("http://127.0.0.1:18080"); properties.setEmbeddingModel("framework-vector-1024");
            properties.setEmbeddingApiKey("fixture-only"); properties.setQdrantGrpcPort(6334);
            var budget = new com.zcx.chenstack.rag.ReleaseProviderBudget("/evidence/provider.ledger", "framework-it", 64, 20);
            var embedding = RagConfig.embedding(properties, budget);
            var knowledge = new com.zcx.chenstack.rag.RagKnowledgeService(properties, lifecycle,
                    new com.zcx.chenstack.rag.QdrantKnowledgeIndex(properties, embedding), embedding);
            try {
                HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (true) {
                    try {
                        if (http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18080/ready")).timeout(Duration.ofSeconds(1)).GET().build(),
                                HttpResponse.BodyHandlers.discarding()).statusCode() == 204) break;
                    } catch (java.io.IOException notReady) { /* Owned mock provider is still loading the candidate. */ }
                    if (System.nanoTime() >= deadline) throw new IllegalStateException("OWNED_MODE_PROVIDER_TIMEOUT"); Thread.sleep(250);
                }
                Map<String,String> hashes = new java.util.TreeMap<>();
                build.resources().forEach((key, value) -> hashes.put(key, com.zcx.chenstack.rag.PublicationManifest.sha256(value)));
                var manifest = new com.zcx.chenstack.rag.PublicationManifest(1, build.gitCommit(), build.artifactSha256(), "framework-it",
                        properties.getCollection(), com.zcx.chenstack.rag.KnowledgeCatalog.hash("http://127.0.0.1:18080|framework-vector-1024|1024|chunk-v1"),
                        hashes, lifecycle.snapshot().epoch(), jdbc.queryForObject("SELECT snapshot_sha256 FROM rag_catalog_state WHERE id=1", String.class),
                        com.zcx.chenstack.rag.PublicationManifest.Operation.PUBLISH, null, false, true,
                        drafts.stream().filter(draft -> com.zcx.chenstack.rag.PublicationManifest.userSourceIds().contains(draft.sourceId()))
                                .map(draft -> new com.zcx.chenstack.rag.PublicationManifest.Topic(draft.sourceId(), draft.version(),
                                        com.zcx.chenstack.rag.KnowledgeCatalog.hash(com.zcx.chenstack.rag.KnowledgeCatalog.normalize(draft.text())))).toList());
                byte[] manifestBytes = mapper.writeValueAsBytes(manifest);
                var grant = new com.zcx.chenstack.rag.PublicationAuthorization.Grant(1, "framework_" + UUID.randomUUID().toString().replace("-", ""),
                        "framework-it", "framework-it", com.zcx.chenstack.rag.PublicationManifest.sha256(manifestBytes), 64, manifest.operation(), true);
                byte[] operationBytes = mapper.writeValueAsBytes(grant);
                grant = com.zcx.chenstack.rag.PublicationAuthorization.verify(operationBytes, manifestBytes, "framework-it", "framework-it", manifest.operation());
                var identity = new com.zcx.chenstack.rag.PublicationManifest.RuntimeIdentity(build.gitCommit(), build.artifactSha256(), "framework-it",
                        manifest.collection(), manifest.embeddingSignature(), manifest.expectedGeneration(), manifest.expectedSnapshotSha256());
                var publication = new com.zcx.chenstack.rag.RagPublicationExecutor(lifecycle, knowledge, manifest, grant,
                        com.zcx.chenstack.rag.PublicationManifest.sha256(operationBytes), manifest.validate(identity, build.resources())).execute();
                if (!"COMPLETED".equals(publication.get("status"))) throw new IllegalStateException("MODE_RAG_PUBLICATION_INCOMPLETE");
                Files.write(Path.of("/evidence/rag-manifest.json"), manifestBytes);
                var login = drafts.stream().filter(draft -> "help-login".equals(draft.sourceId())).findFirst().orElseThrow();
                Files.writeString(Path.of("/evidence/rag-fixture.json"), mapper.writeValueAsString(Map.of("title", login.title(),
                        "quote", login.text().split("。", 2)[0] + "。")));

                var user = new com.zcx.chenstack.domain.entity.SysUser(); user.setId(8); user.setSysPermissions(List.of());
                var role = new com.zcx.chenstack.domain.entity.SysRole(); role.setRole("admin"); user.setSysRoles(List.of(role));
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                        new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                                new com.zcx.chenstack.domain.entity.LoginUser(user), null, List.of()));
                String fact = "FRAMEWORK_PUBLISHED_WIKI_SENTINEL Redis TTL 为 60 seconds。";
                jdbc.update("INSERT INTO article(id,user_id,title,description,content,tag,edit_status,examine_status,visible_range,is_deleted,create_time,update_time) VALUES(901,8,?,'隔离合成来源',?,'redis',0,1,0,0,NOW(),NOW())",
                        "Framework Redis 合法来源", "<p>" + fact + "</p>");
                var manager = new org.springframework.jdbc.datasource.DataSourceTransactionManager(source);
                var security = new com.zcx.chenstack.security.AdminSecurity();
                var foundation = new com.zcx.chenstack.wiki.WikiFoundation(jdbc, manager,
                        new com.zcx.chenstack.wiki.WikiSourceReader(jdbc, lifecycle, mapper), security);
                var key = new com.zcx.chenstack.wiki.WikiFoundation.SourceKey(com.zcx.chenstack.wiki.WikiFoundation.SourceType.ARTICLE, "901");
                var inspected = foundation.inspect(key); var snapshot = foundation.authorize(key, inspected.fingerprint());
                var model = new com.zcx.chenstack.wiki.WikiModel() {
                    public String id() { return "framework-wiki-fixture"; }
                    public boolean external() { return false; }
                    public Reply generate(String system, String input, int outputTokens) {
                        return new Reply(system.startsWith("WIKI_MAP")
                                ? "{\"facts\":[{\"topic\":\"redis\",\"key\":\"ttl\",\"value\":\"60 seconds\",\"quote\":\"" + fact + "\"}]}"
                                : "{\"title\":\"Framework Redis 已发布知识\",\"summary\":[{\"text\":\"" + fact + "\",\"facts\":[\"f1\"]}],\"sections\":[{\"heading\":\"过期规则\",\"claims\":[{\"text\":\"Redis TTL 为 60 seconds。\",\"facts\":[\"f1\"]}]}],\"related\":[]}", 24L);
                    }
                };
                String request = "framework-wiki";
                new com.zcx.chenstack.wiki.WikiCompilationService(foundation, jdbc, mapper, model).compile(request, List.of(snapshot.id()),
                        new com.zcx.chenstack.wiki.WikiCompilationService.Options(2, 100_000, 512, Duration.ofSeconds(10), Set.of()));
                var wiki = new com.zcx.chenstack.wiki.WikiPublicationService(jdbc, manager, foundation, security);
                var revision = wiki.create(request, null, "Framework Redis 已发布知识", "合法来源的隔离 Wiki", null);
                wiki.submit(revision.id(), revision.bodyHash()); wiki.approve(revision.id(), revision.bodyHash(), true, true);
                wiki.publish(revision.id(), revision.bodyHash(), null);
                var hidden = wiki.create(request, null, "Framework Redis 未发布草稿", "隔离保密断言", "# FRAMEWORK_DRAFT_PRIVATE_SENTINEL");
                Files.writeString(Path.of("/evidence/wiki-fixture.json"), mapper.writeValueAsString(Map.of("pageId", revision.pageId(),
                        "revisionId", revision.id(), "privatePageId", hidden.pageId(), "articleId", 901)));
                System.out.println("FRAMEWORK_MODE_FIXTURES=REAL_P1_P2_P3_AND_G2_PUBLICATION;EXTERNAL_PROVIDER_CALLS=0");
            } finally {
                org.springframework.security.core.context.SecurityContextHolder.clearContext();
                try { knowledge.close(); } finally { lifecycle.close(); }
            }
        }
    }

    private List<com.fasterxml.jackson.databind.JsonNode> sse(HttpClient http, String base, String token, String id) throws Exception {
        return sse(http, base, token, id, "你好");
    }

    private List<com.fasterxml.jackson.databind.JsonNode> sse(HttpClient http, String base, String token, String id, String question) throws Exception {
        var reply = http.send(HttpRequest.newBuilder(URI.create(base + "/ai/customer-service/events"))
                .timeout(Duration.ofSeconds(20)).header("Authorization", token).header("Origin", "http://localhost")
                .header("Content-Type", "application/json").header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(new ObjectMapper().writeValueAsString(Map.of("message", question,
                        "chatId", "framework-" + id, "mode", "chat")), StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, reply.statusCode(), reply.body());
        assertTrue(reply.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        List<com.fasterxml.jackson.databind.JsonNode> events = new ArrayList<>();
        for (String line : reply.body().lines().filter(value -> value.startsWith("data:")).toList()) {
            events.add(new ObjectMapper().readTree(line.substring(5).strip()));
        }
        assertFalse(events.isEmpty(), reply.body());
        return events;
    }

    /** Only CREATE TABLE blocks are imported; historical INSERT records never enter this fixture. */
    private void applyBusinessDdlOnly(java.sql.Connection connection) throws Exception {
        try (var input = Files.newBufferedReader(Path.of("../sql/chen_stack.sql"))) {
            StringBuilder ddl = null;
            for (String line; (line = input.readLine()) != null;) {
                if (line.startsWith("CREATE TABLE ")) ddl = new StringBuilder();
                if (ddl != null) {
                    ddl.append(line).append('\n');
                    if (line.startsWith(") ENGINE") && line.stripTrailing().endsWith(";")) {
                        try (var command = connection.createStatement()) { command.execute(ddl.toString()); }
                        ddl = null;
                    }
                }
            }
        }
    }

    private int start(List<String> containers, String network, String owner, String suffix, String image,
                      int port, String environment, String tmpfs, String... arguments) throws Exception {
        String name = network + "-" + suffix;
        Path envFile = temporary.resolve(suffix + ".env");
        Files.writeString(envFile, environment);
        List<String> command = new ArrayList<>(List.of("run", "--pull=never", "-d", "--name", name,
                "--network", network, "--dns", "127.0.0.1", "--label", "chen.framework.it=" + owner, "--tmpfs", tmpfs,
                "--env-file", envFile.toString(), "-p", "127.0.0.1::" + port, image));
        command.addAll(List.of(arguments));
        docker(command.toArray(String[]::new));
        containers.add(name);
        String published = docker("port", name, port + "/tcp");
        assertTrue(published.startsWith("127.0.0.1:"));
        return Integer.parseInt(published.substring(published.lastIndexOf(':') + 1));
    }

    private String docker(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("docker")); command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        if ("compose".equals(arguments[0])) builder.environment().remove("AI_KNOWLEDGE_MODE");
        Process process = builder.start();
        var captured = new java.util.concurrent.FutureTask<byte[]>(() -> process.getInputStream().readAllBytes());
        Thread.ofVirtual().start(captured);
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly(); throw new AssertionError("Owned Docker command timed out: " + arguments[0]);
        }
        String output = new String(captured.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8).strip();
        assertEquals(0, process.exitValue(), "Owned Docker " + arguments[0] + " failed: " + output);
        return output;
    }
}
