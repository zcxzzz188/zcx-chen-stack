package com.zcx.chenstack.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zcx.chenstack.config.RagProperties;
import com.zcx.chenstack.config.RagPublicationConfiguration;
import com.zcx.chenstack.config.RagPublicationProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson2.autoconfigure.Jackson2AutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.webmvc.autoconfigure.WebMvcAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Explicit, offline-provider integration suite. Run from the backend module:
 * mvn -Dtest=PublicationIntegrationIT -Dchen.release.it=true test
 * Requires the local mysql:8.0 and qdrant/qdrant:v1.13.6 images; never pulls images.
 * Creates and removes only its own randomly named, labeled dedicated bridge and containers.
 * Published ports bind only 127.0.0.1; no existing network, volume, config or provider key is used.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublicationIntegrationIT {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final String ENVIRONMENT = "isolated-it";
    private static final String OPERATOR = "synthetic-operator";
    private static final String BUILD = "a".repeat(40);
    private static final String ARTIFACT = "b".repeat(64);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @TempDir static Path temporary;
    private DockerFixture docker;
    private List<KnowledgeDocument> drafts;
    private Map<String, byte[]> resources;
    private Path runtimeJar;

    @BeforeAll void startOnlyTaskOwnedInfrastructure() throws Exception {
        assertEquals("true", System.getProperty("chen.release.it"),
                "Explicit integration opt-in required; this suite must not silently skip.");
        drafts = syntheticDrafts();
        resources = Map.of(PublicationManifest.PLATFORM_HELP_RESOURCE, JSON.writeValueAsBytes(drafts),
                PublicationManifest.CAPABILITIES_RESOURCE, "{\"syntheticFixture\":true}\n".getBytes(StandardCharsets.UTF_8));
        docker = new DockerFixture(temporary);
        docker.start();
    }

    @AfterAll void stopOnlyTaskOwnedInfrastructure() throws Exception {
        if (docker != null) docker.close();
    }

    @Test void isolatedHistoryVisibilityFiltersBeforePaginationAndCount() throws Exception {
        EmptyTarget target = emptyTarget();
        try (var connection = target.source.getConnection(); var statement = connection.createStatement()) {
            for (String ddl : List.of(
                    "CREATE TABLE sys_user(id INT PRIMARY KEY,nickname VARCHAR(40),avatar VARCHAR(80),is_deleted TINYINT NOT NULL)",
                    "CREATE TABLE article(id INT PRIMARY KEY,user_id INT,title VARCHAR(80),cover_url VARCHAR(80),examine_status INT,edit_status INT,is_deleted TINYINT,visible_range INT)",
                    "CREATE TABLE history(id BIGINT PRIMARY KEY,user_id INT,article_id INT,view_time DATETIME,is_deleted TINYINT)",
                    "CREATE TABLE follow(follower_id INT,followed_id INT,PRIMARY KEY(follower_id,followed_id))")) statement.execute(ddl);
            statement.executeUpdate("INSERT INTO sys_user VALUES (7,'viewer','',0),(8,'other','',0),(9,'followed','',0),(10,'unfollowed','',0),(11,'deleted','',1)");
            statement.executeUpdate("INSERT INTO follow VALUES (7,9)");
            statement.executeUpdate("""
                    INSERT INTO article VALUES
                    (201,7,'own private','',1,0,0,1),
                    (202,8,'other public','',1,0,0,0),
                    (203,9,'followed followers','',1,0,0,2),
                    (211,8,'foreign private','',1,0,0,1),
                    (212,10,'unfollowed followers','',1,0,0,2),
                    (213,8,'pending review','',0,0,0,0),
                    (214,8,'draft','',1,1,0,0),
                    (215,8,'deleted article','',1,0,1,0),
                    (216,11,'deleted author','',1,0,0,0),
                    (217,8,'deleted history','',1,0,0,0),
                    (218,7,'illegal own visibility','',1,0,0,99),
                    (219,7,'null own visibility','',1,0,0,NULL)
                    """);
            statement.executeUpdate("""
                    INSERT INTO history VALUES
                    (1001,7,201,'2026-01-01 10:00:30',0),
                    (1002,7,202,'2026-01-01 10:00:20',0),
                    (1003,7,203,'2026-01-01 10:00:10',0),
                    (1101,7,211,'2026-01-01 10:01:01',0),
                    (1102,7,212,'2026-01-01 10:01:02',0),
                    (1103,7,213,'2026-01-01 10:01:03',0),
                    (1104,7,214,'2026-01-01 10:01:04',0),
                    (1105,7,215,'2026-01-01 10:01:05',0),
                    (1106,7,216,'2026-01-01 10:01:06',0),
                    (1107,7,217,'2026-01-01 10:01:07',1),
                    (1108,8,202,'2026-01-01 10:01:08',0),
                    (1109,7,218,'2026-01-01 10:01:09',0),
                    (1110,8,201,'2026-01-01 10:01:10',0),
                    (1111,7,219,'2026-01-01 10:01:11',0)
                    """);
        }
        org.apache.ibatis.session.Configuration configuration = new org.apache.ibatis.session.Configuration();
        String resource = "mapper/HistoryMapper.xml";
        try (var reader = Resources.getResourceAsReader(resource)) {
            new XMLMapperBuilder(reader, configuration, resource, configuration.getSqlFragments()).parse();
        }
        MappedStatement mapped = configuration.getMappedStatement("com.zcx.chenstack.mapper.HistoryMapper.getUserHistoryList");
        for (boolean following : List.of(true, false)) {
            for (int viewer : List.of(7, 8, 99)) {
                List<Long> expected = viewer == 7
                        ? following ? List.of(1001L, 1002L, 1003L) : List.of(1001L, 1002L)
                        : viewer == 8 ? List.of(1108L) : List.of();
                Map<String, Object> parameters = Map.of("userId", viewer);
                BoundSql original = mapped.getBoundSql(parameters);
                String countSql = new PaginationInnerInterceptor(DbType.MYSQL).autoCountSql(new Page<>(1, 2), original.getSql());
                BoundSql count = new BoundSql(configuration, countSql, original.getParameterMappings(), parameters);
                try (var connection = target.source.getConnection(); var query = connection.prepareStatement(countSql)) {
                    new DefaultParameterHandler(mapped, parameters, count).setParameters(query);
                    try (var rows = query.executeQuery()) {
                        assertTrue(rows.next()); assertEquals(expected.size(), rows.getLong(1), "Count must use the viewer's real visibility scope.");
                    }
                }
                for (int offset : List.of(0, 2)) {
                    String pageSql = original.getSql() + " LIMIT 2 OFFSET " + offset;
                    BoundSql page = new BoundSql(configuration, pageSql, original.getParameterMappings(), parameters);
                    List<Long> actual = new ArrayList<>();
                    try (var connection = target.source.getConnection(); var query = connection.prepareStatement(pageSql)) {
                        new DefaultParameterHandler(mapped, parameters, page).setParameters(query);
                        try (var rows = query.executeQuery()) { while (rows.next()) actual.add(rows.getLong("id")); }
                    }
                    assertEquals(expected.subList(Math.min(offset, expected.size()), Math.min(offset + 2, expected.size())), actual,
                            "Filtering must precede pagination, viewer=" + viewer + ", following=" + following + ", offset=" + offset);
                }
            }
            if (following) sql(target.source, "DELETE FROM follow WHERE follower_id=7 AND followed_id=9");
        }
        System.out.println("PUBLICATION_IT_HISTORY_SQL mapper=REAL_MYBATIS count=REAL_PAGINATION_INTERCEPTOR viewers=7,8,99 followRevocation=PASS");
    }

    @Test void twoSyntheticAccountsCannotReadEachOthersEditableArticles() throws Exception {
        EmptyTarget target = emptyTarget();
        try (var connection = target.source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE article(id INT PRIMARY KEY,user_id INT,title VARCHAR(80),tag VARCHAR(80),description TEXT,
                    content TEXT,cover_url VARCHAR(200),read_count INT,like_count INT,comment_count INT,collect_count INT,
                    examine_status INT,edit_status INT,visible_range INT,reprint_type INT,reprint_url VARCHAR(200),
                    create_time DATETIME,update_time DATETIME,is_deleted TINYINT)
                    """);
            statement.executeUpdate("INSERT INTO article(id,user_id,title,content,edit_status,examine_status,is_deleted,update_time) VALUES (201,7,'OWNER_A_TITLE','OWNER_A_PRIVATE_BODY',1,0,0,NOW()),(202,8,'OWNER_B_TITLE','OWNER_B_PRIVATE_BODY',1,0,0,NOW())");
        }
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("quality-isolated",
                new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), target.source));
        var pagination = new com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor();
        pagination.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        configuration.addInterceptor(pagination);
        configuration.addMapper(com.zcx.chenstack.mapper.ArticleMapper.class);
        var factory = new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
        try (var session = factory.openSession(true)) {
            var service = new com.zcx.chenstack.service.EditableArticleService(session.getMapper(com.zcx.chenstack.mapper.ArticleMapper.class));
            for (int user : List.of(7, 8)) {
                int ownId = user == 7 ? 201 : 202;
                int foreignId = user == 7 ? 202 : 201;
                var cards = service.listForUser(user, 1, 5);
                assertEquals(List.of(ownId), cards.articles().stream().map(com.zcx.chenstack.domain.vo.CustomerServiceEvent.EditableArticle::articleId).toList());
                assertEquals(ownId, service.requireEditable(user, ownId).articleId());
                assertThrows(com.zcx.chenstack.exception.BlogException.class, () -> service.requireEditable(user, foreignId));
                assertThrows(com.zcx.chenstack.exception.BlogException.class, () -> service.loadForUser(user, foreignId));
                assertFalse(JSON.writeValueAsString(cards).contains("PRIVATE_BODY"));
            }
        }
    }

    @Test void validPublicationPersistsExactly39ApprovedSources() throws Exception {
        try (Fixture f = fixture()) {
            OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
            execute(f, input);
            assertReady(f);
            assertEquals(1, f.catalog().epoch());
            assertEquals(PublicationManifest.userSourceIds(), f.catalog().chunks().stream()
                    .map(d -> (String) d.getMetadata().get("source_id")).collect(java.util.stream.Collectors.toSet()));
            assertEquals(39, f.catalog().chunks().size());
            assertEquals(40, points(f).size()); // 39 facts plus the embedding-signature marker.
            assertEquals(13, f.catalog().documents().stream().filter(d -> "NOT_PUBLISHED".equals(d.visibility())).count());
            assertEquals(39, scalar(f.source, "SELECT COUNT(*) FROM rag_source_state WHERE JSON_UNQUOTE(JSON_EXTRACT(document_json,'$.reviewStatus'))='DOCUMENT_REVIEWED'"));
            assertEquals("COMPLETED", f.store.operation(input.grant.operationId()).status());
            for (KnowledgeDocument draft : drafts) {
                KnowledgeEvidence evidence = f.knowledge.retrieveByTitle(draft.title());
                assertEquals(PublicationManifest.userSourceIds().contains(draft.sourceId()), !evidence.chunks().isEmpty(), draft.sourceId());
            }
            KnowledgeEvidence semantic = f.knowledge.retrieve("synthetic login help");
            assertFalse(semantic.chunks().isEmpty(), "The real Qdrant search must return verified synthetic evidence.");
            assertTrue(semantic.chunks().stream().allMatch(d -> PublicationManifest.userSourceIds()
                    .contains(d.getMetadata().get("source_id"))));
        }
    }

    @Test void controlledBootstrapInitializesOnlyMigratedEmptyTablesAndIsResumable() throws Exception {
        EmptyTarget target = emptyTarget();
        RagProperties properties = properties(target.collection, docker.grpcPort);
        String signature = KnowledgeCatalog.hash(properties.getEmbeddingBaseUrl() + "|" + properties.getEmbeddingModel() + "|1024|chunk-v1");
        Map<String, String> hashes = new TreeMap<>(); resources.forEach((path, bytes) -> hashes.put(path, PublicationManifest.sha256(bytes)));
        PublicationManifest manifest = new PublicationManifest(1, BUILD, ARTIFACT, ENVIRONMENT, target.collection, signature,
                hashes, 0, "EMPTY", PublicationManifest.Operation.PUBLISH, null, true, true,
                drafts.stream().filter(d -> PublicationManifest.userSourceIds().contains(d.sourceId()))
                        .map(d -> new PublicationManifest.Topic(d.sourceId(), d.version(), KnowledgeCatalog.hash(d.text()))).toList());
        OperationInput confirmed = operationInput(manifest, 20);
        List<KnowledgeDocument> validated = manifest.validate(new PublicationManifest.RuntimeIdentity(BUILD, ARTIFACT,
                ENVIRONMENT, target.collection, signature, 0, "EMPTY"), resources);
        OperationInput input = new OperationInput(manifest, confirmed.bytes, confirmed.operationBytes, confirmed.grant, validated);
        KnowledgeCatalog opened = RagPublicationExecutor.openOrInitialize(target.source, drafts, manifest, confirmed.grant,
                PublicationManifest.sha256(confirmed.operationBytes));
        try (Fixture f = new Fixture(target.source, target.schema, target.collection, opened)) {
            assertEquals(0, opened.epoch());
            assertEquals(52, opened.sourceIds().size());
            assertTrue(opened.chunks().isEmpty());
            assertEquals(1, scalar(target.source, "SELECT COUNT(*) FROM rag_release_event WHERE event_type='BOOTSTRAP'"));
            execute(f, input);
            assertReady(f);
        }
        KnowledgeCatalog resumed = RagPublicationExecutor.openOrInitialize(target.source, drafts, manifest, confirmed.grant,
                PublicationManifest.sha256(confirmed.operationBytes));
        try (Fixture f = new Fixture(target.source, target.schema, target.collection, resumed)) {
            assertRecoveryRequired(f);
            restore(f, input);
            assertReady(f);
            assertEquals(1, resumed.epoch());
            assertEquals(0, f.model.calls.get());
            assertEquals(1, scalar(target.source, "SELECT COUNT(*) FROM rag_release_event WHERE event_type='BOOTSTRAP'"));
        }
    }

    @Test void simultaneousExecutorCannotTakeSameProcessPublicationLease() throws Exception {
        try (Fixture f = fixture(); var workers = Executors.newSingleThreadExecutor()) {
            OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
            f.model.entered = new CountDownLatch(1); f.model.proceed = new CountDownLatch(1);
            var first = workers.submit(() -> execute(f, input));
            try {
                assertTrue(f.model.entered.await(10, TimeUnit.SECONDS));
                IllegalStateException collision = assertThrows(IllegalStateException.class, () -> execute(f, input));
                assertEquals("RELEASE_ALREADY_RUNNING", collision.getMessage());
            } finally { f.model.proceed.countDown(); }
            assertEquals("COMPLETED", first.get(30, TimeUnit.SECONDS).get("status"));
            assertReady(f);
            assertEquals(4, f.model.calls.get());
            assertEquals(1, scalar(f.source, "SELECT COUNT(*) FROM rag_release_operation"));
        }
    }

    @Test void existingForeignCollectionCannotBeAdoptedEvenWithMatchingEmbeddingSignature() throws Exception {
        try (Fixture f = fixture()) {
            qdrant("PUT", "/collections/" + f.collection, Map.of("vectors", Map.of("size", 1024, "distance", "Cosine")));
            List<Integer> vector = new ArrayList<>(Collections.nCopies(1024, 0)); vector.set(0, 1);
            String marker = UUID.nameUUIDFromBytes("chen-rag-index-binding-v1".getBytes(StandardCharsets.UTF_8)).toString();
            qdrant("PUT", "/collections/" + f.collection + "/points?wait=true", Map.of("points", List.of(Map.of(
                    "id", marker, "vector", vector, "payload", Map.of("embedding_signature", f.knowledge.embeddingSignature(), "release_owner", "foreign-owner")))));
            executeFailure(f, input(f, PublicationManifest.Operation.PUBLISH, 20, null));
            assertEquals(0, f.catalog().epoch());
            assertEquals(0, f.model.calls.get());
            assertRecoveryRequired(f);
            assertEquals(1, points(f).size());
            assertEquals("foreign-owner", points(f).getFirst().path("payload").path("release_owner").asText());
        }
    }

    @Test void differentCatalogDatabaseCannotAdoptSameEnvironmentCollection() throws Exception {
        try (Fixture first = fixture()) {
            execute(first, input(first, PublicationManifest.Operation.PUBLISH, 20, null));
            assertReady(first);
            List<JsonNode> before = points(first);
            EmptyTarget secondTarget = emptyTarget();
            JdbcCatalogStore.initialize(secondTarget.source, drafts);
            try (Fixture second = new Fixture(secondTarget.source, secondTarget.schema, first.collection)) {
                assertNotEquals(first.schema, second.schema);
                assertNotEquals(first.store.databaseIdentityHash(), second.store.databaseIdentityHash());
                executeFailure(second, input(second, PublicationManifest.Operation.PUBLISH, 20, null));
                assertEquals(0, second.catalog().epoch());
                assertEquals(0, second.model.calls.get());
                assertEquals(0, scalar(second.source, "SELECT COUNT(*) FROM rag_source_state WHERE JSON_UNQUOTE(JSON_EXTRACT(document_json,'$.visibility'))='PUBLIC'"));
                assertRecoveryRequired(second);
                assertEquals(before, points(first), "Another database must not replace any existing target payload or owner marker.");
                assertReady(first);
            }
        }
    }

    @Test void invalidOperationRecordCannotMutateCatalogOrIndex() throws Exception {
        try (Fixture f = fixture()) {
            OperationInput valid = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
            for (var entry : Map.<String, Object>of("confirmed", false, "operator", "other", "schemaVersion", 2,
                    "manifestSha256", "f".repeat(64), "unexpected", "denied").entrySet()) {
                var record = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.valueToTree(valid.grant);
                record.set(entry.getKey(), JSON.valueToTree(entry.getValue()));
                assertThrows(SecurityException.class, () -> verify(JSON.writeValueAsBytes(record), valid.bytes, valid.manifest.operation()));
            }
            assertThrows(SecurityException.class, () -> verify(JSON.writeValueAsBytes(Map.of(
                    "payload", "e30", "signature", "legacy")), valid.bytes, valid.manifest.operation()));
            assertUntouched(f);
        }
    }

    @Test void changedResourceBytesCannotUseOriginalOperationInput() throws Exception {
        try (Fixture f = fixture()) {
            OperationInput valid = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
            Map<String, byte[]> changed = new HashMap<>(resources);
            changed.put(PublicationManifest.PLATFORM_HELP_RESOURCE, (new String(resources.get(PublicationManifest.PLATFORM_HELP_RESOURCE), StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8));
            assertThrows(SecurityException.class, () -> valid.manifest.validate(identity(f), changed));
            assertUntouched(f);
        }
    }

    @Test void wrongRuntimeIdentityCannotPublish() throws Exception {
        try (Fixture f = fixture()) {
            OperationInput valid = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
            var original = identity(f);
            for (var wrong : List.of(
                    new PublicationManifest.RuntimeIdentity(BUILD, ARTIFACT, "other", f.collection, f.knowledge.embeddingSignature(), 0, f.store.snapshotHash()),
                    new PublicationManifest.RuntimeIdentity(BUILD, ARTIFACT, ENVIRONMENT, "chen_wrong", f.knowledge.embeddingSignature(), 0, f.store.snapshotHash()),
                    new PublicationManifest.RuntimeIdentity(BUILD, "c".repeat(64), ENVIRONMENT, f.collection, f.knowledge.embeddingSignature(), 0, f.store.snapshotHash()))) {
                assertThrows(SecurityException.class, () -> valid.manifest.validate(wrong, resources));
            }
            assertThrows(SecurityException.class, () -> PublicationAuthorization.verify(valid.operationBytes, valid.bytes,
                    "other-operator", ENVIRONMENT, valid.manifest.operation()));
            assertEquals(0, original.generation());
            assertUntouched(f);
        }
    }

    @Test void staleCatalogPreconditionCannotPublish() throws Exception {
        try (Fixture f = fixture()) {
            OperationInput old = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
            f.lifecycle.update(drafts.getFirst(), 0);
            assertThrows(SecurityException.class, () -> old.manifest.validate(identity(f), resources));
            assertThrows(RuntimeException.class, () -> execute(f, old));
            assertEquals(1, f.catalog().epoch());
            assertFalse(f.knowledge.publicationReady());
            assertEquals(0, f.model.calls.get());
        }
    }

    @Test void adminOrHistoryCannotEnterApprovedSet() throws Exception {
        try (Fixture f = fixture()) {
            OperationInput valid = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
            for (String excluded : List.of("help-history", "help-admin-articles")) {
                List<PublicationManifest.Topic> changed = new ArrayList<>(valid.manifest.topics());
                changed.set(0, new PublicationManifest.Topic(excluded, "synthetic-1", "e".repeat(64)));
                PublicationManifest forbidden = withTopics(valid.manifest, changed);
                OperationInput confirmedForbidden = operationInput(forbidden, 20);
                assertNotNull(confirmedForbidden.grant); // Operator confirmation does not relax content scope.
                assertThrows(SecurityException.class, () -> forbidden.validate(identity(f), resources));
            }
            assertUntouched(f);
        }
    }

    @Test void completedReplayDoesNotEmbedOrAdvanceGeneration() throws Exception {
        try (Fixture f = fixture()) {
            OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
            execute(f, input);
            int calls = f.model.calls.get();
            long events = scalar(f.source, "SELECT COUNT(*) FROM rag_release_event");
            execute(f, input);
            assertReady(f);
            assertEquals(1, f.catalog().epoch());
            assertEquals(calls, f.model.calls.get());
            assertEquals(events, scalar(f.source, "SELECT COUNT(*) FROM rag_release_event"));
        }
    }

    @Test void operationIdReplayWithChangedBudgetIsRejectedBeforeProviderWork() throws Exception {
        try (Fixture f = fixture()) {
            OperationInput original = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
            execute(f, original);
            var changedGrant = new PublicationAuthorization.Grant(1, original.grant.operationId(), OPERATOR, ENVIRONMENT,
                    original.grant.manifestSha256(), 21, original.grant.allowedOperation(), true);
            byte[] changed = JSON.writeValueAsBytes(changedGrant);
            OperationInput replay = new OperationInput(original.manifest, original.bytes, changed,
                    verify(changed, original.bytes, original.manifest.operation()), original.validatedDrafts);
            int calls = f.model.calls.get();
            long events = scalar(f.source, "SELECT COUNT(*) FROM rag_release_event");
            assertThrows(RuntimeException.class, () -> execute(f, replay));
            assertEquals(calls, f.model.calls.get());
            assertEquals(events, scalar(f.source, "SELECT COUNT(*) FROM rag_release_event"));
            assertEquals(PublicationManifest.sha256(original.operationBytes), f.store.operation(original.grant.operationId()).operationHash());
        }
    }

    @Test void legacySignedModeOrLegacyValidationEventCannotRestoreReadAuthority() throws Exception {
        for (boolean changeMode : List.of(true, false)) {
            Fixture f = fixture();
            OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
            execute(f, input);
            if (changeMode) sql(f.source, "UPDATE rag_release_operation SET approver_id='LEGACY_ED25519'");
            else sql(f.source, "UPDATE rag_release_event SET details_code='SIGNATURE_AND_SCOPE_VERIFIED' WHERE event_type='VALIDATED'");
            try (Fixture restarted = reopen(f)) { assertRestoreRejected(restarted, input); }
        }
    }

    @Test void zeroEmbeddingUpgradeReusesPhysicalIndexAndRestartsWithNewArtifactIdentity() throws Exception {
        try (Fixture f = fixture()) {
            OperationInput published = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
            execute(f, published);
            String snapshot = f.store.snapshotHash();
            var originalReceipt = f.store.completedReceipt(published.grant.manifestSha256());
            int calls = f.model.calls.get();
            OperationInput upgrade = zeroEmbeddingUpgrade(f);
            assertThrows(SecurityException.class, () -> published.manifest.validate(upgradeIdentity(f, upgrade), resources));
            assertEquals("COMPLETED", execute(f, upgrade).get("status"));
            assertEquals(calls, f.model.calls.get());
            assertEquals(1, f.catalog().epoch());
            assertEquals(snapshot, f.store.snapshotHash());
            assertEquals(39, f.catalog().documents().stream().filter(d -> "DOCUMENT_REVIEWED".equals(d.reviewStatus())).count());
            var upgradedReceipt = f.store.completedReceipt(upgrade.grant.manifestSha256());
            assertEquals(originalReceipt.indexHash(), upgradedReceipt.indexHash());
            assertEquals(originalReceipt.operation().indexAuthority(), upgradedReceipt.operation().indexAuthority());
            assertEquals(0, upgradedReceipt.operation().embeddingRequests());
            try (Fixture restarted = reopen(f)) {
                long events = scalar(restarted.source, "SELECT COUNT(*) FROM rag_release_event");
                assertEquals("READ_ONLY_RECOVERED", RagPublicationRecovery.restore(restarted.lifecycle, restarted.knowledge,
                        upgrade.bytes, OPERATOR, ENVIRONMENT, upgradeIdentity(restarted, upgrade), resources).get("status"));
                assertReady(restarted);
                assertEquals("COMPLETED", execute(restarted, upgrade).get("status"));
                assertEquals(events, scalar(restarted.source, "SELECT COUNT(*) FROM rag_release_event"));
                assertEquals(0, restarted.model.calls.get());
                assertEquals(snapshot, restarted.store.snapshotHash());
            }
        }
    }

    @Test void zeroEmbeddingUpgradeCannotRepairMissingPhysicalChunks() throws Exception {
        try (Fixture f = fixture()) {
            execute(f, input(f, PublicationManifest.Operation.PUBLISH, 20, null));
            String snapshot = f.store.snapshotHash();
            int calls = f.model.calls.get();
            qdrant("POST", "/collections/" + f.collection + "/points/delete?wait=true",
                    Map.of("points", List.of(f.catalog().chunks().getFirst().getId())));
            OperationInput upgrade = zeroEmbeddingUpgrade(f);
            assertThrows(RuntimeException.class, () -> execute(f, upgrade));
            assertRecoveryRequired(f);
            assertEquals(calls, f.model.calls.get());
            assertEquals(snapshot, f.store.snapshotHash());
            assertEquals(0, f.store.operation(upgrade.grant.operationId()).embeddingRequests());
        }
    }

    @Test void zeroEmbeddingUpgradeRequiresExistingTrustedReceiptAndCannotInitialize() throws Exception {
        try (Fixture f = fixture()) {
            OperationInput published = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
            execute(f, published);
            int calls = f.model.calls.get();
            String snapshot = f.store.snapshotHash();
            sql(f.source, "UPDATE rag_release_event SET details_code='LEGACY_OR_FORGED' WHERE event_type='VALIDATED'");
            assertThrows(RuntimeException.class, () -> execute(f, zeroEmbeddingUpgrade(f)));
            assertRecoveryRequired(f);
            assertEquals(calls, f.model.calls.get());
            assertEquals(snapshot, f.store.snapshotHash());
        }
        try (Fixture f = fixture()) {
            PublicationManifest base = manifest(f, PublicationManifest.Operation.RECOVER, null);
            PublicationManifest creating = new PublicationManifest(base.schemaVersion(), base.gitCommit(), base.artifactSha256(),
                    base.environment(), base.collection(), base.embeddingSignature(), base.resourceSha256(), base.expectedGeneration(),
                    base.expectedSnapshotSha256(), base.operation(), null, false, true, base.topics());
            OperationInput confirmed = operationInput(creating, 0);
            OperationInput invalid = new OperationInput(creating, confirmed.bytes, confirmed.operationBytes, confirmed.grant,
                    base.validate(identity(f), resources));
            long events = scalar(f.source, "SELECT COUNT(*) FROM rag_release_event");
            assertThrows(SecurityException.class, () -> execute(f, invalid));
            assertEquals(events, scalar(f.source, "SELECT COUNT(*) FROM rag_release_event"));
            assertEquals(0, f.model.calls.get());
        }
    }

    @Test void zeroEmbeddingRecoveryCannotBootstrapBeforeExecutorStarts() throws Exception {
        EmptyTarget target = emptyTarget();
        Map<String, String> hashes = new TreeMap<>();
        resources.forEach((path, bytes) -> hashes.put(path, PublicationManifest.sha256(bytes)));
        PublicationManifest manifest = new PublicationManifest(1, BUILD, ARTIFACT, ENVIRONMENT, target.collection,
                "c".repeat(64), hashes, 0, "EMPTY", PublicationManifest.Operation.RECOVER, null, true, false,
                drafts.stream().filter(d -> PublicationManifest.userSourceIds().contains(d.sourceId()))
                        .map(d -> new PublicationManifest.Topic(d.sourceId(), d.version(), KnowledgeCatalog.hash(d.text()))).toList());
        OperationInput confirmed = operationInput(manifest, 0);
        assertThrows(SecurityException.class, () -> RagPublicationExecutor.openOrInitialize(target.source, drafts,
                manifest, confirmed.grant, PublicationManifest.sha256(confirmed.operationBytes)));
        for (String table : List.of("rag_catalog_state", "rag_source_state", "rag_release_operation", "rag_release_event")) {
            assertEquals(0, scalar(target.source, "SELECT COUNT(*) FROM " + table), table);
        }
    }

    @Test void restartRestoresCompletedReceiptWithoutEmbeddingOrPersistentWrites() throws Exception {
        Fixture f = fixture();
        OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
        execute(f, input);
        String oldAuthority = f.lifecycle.authorityId();
        long spent = f.store.operation(input.grant.operationId()).embeddingRequests();
        long events = scalar(f.source, "SELECT COUNT(*) FROM rag_release_event");
        List<JsonNode> physicalBefore = points(f);
        Fixture restarted = reopen(f);
        try (restarted) {
            assertNotEquals(oldAuthority, restarted.lifecycle.authorityId());
            assertRecoveryRequired(restarted);
            restore(restarted, input);
            assertReady(restarted);
            assertEquals(1, restarted.catalog().epoch());
            assertEquals(spent, restarted.store.operation(input.grant.operationId()).embeddingRequests());
            assertEquals(oldAuthority, restarted.store.operation(input.grant.operationId()).indexAuthority());
            assertEquals(0, restarted.model.calls.get());
            assertEquals(events, scalar(restarted.source, "SELECT COUNT(*) FROM rag_release_event"));
            assertEquals(physicalBefore, points(restarted));
        }
    }

    @Test void removingOperationRecordFileDoesNotPreventReadOnlyRestartRecovery() throws Exception {
        Fixture f = fixture();
        OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
        Path operationFile = temporary.resolve("removable-operation-" + UUID.randomUUID() + ".json");
        Files.write(operationFile, input.operationBytes);
        execute(f, input);
        Files.delete(operationFile);
        assertFalse(Files.exists(operationFile));
        long spent = f.store.operation(input.grant.operationId()).embeddingRequests();
        long events = scalar(f.source, "SELECT COUNT(*) FROM rag_release_event");
        Fixture restarted = reopen(f);
        try (restarted) {
            restore(restarted, input);
            assertReady(restarted);
            assertEquals(0, restarted.model.calls.get());
            assertEquals(spent, restarted.store.operation(input.grant.operationId()).embeddingRequests());
            assertEquals(events, scalar(restarted.source, "SELECT COUNT(*) FROM rag_release_event"));
        }
    }

    @Test void missingPhysicalChunkCannotRestoreReadAuthority() throws Exception {
        Fixture f = fixture();
        OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
        execute(f, input);
        String chunk = f.catalog().chunks().getFirst().getId();
        qdrant("POST", "/collections/" + f.collection + "/points/delete?wait=true", Map.of("points", List.of(chunk)));
        try (Fixture restarted = reopen(f)) { assertRestoreRejected(restarted, input); }
    }

    @Test void readOnlyRecoveryRejectsManifestArtifactEnvironmentAndOperatorDrift() throws Exception {
        Fixture f = fixture();
        OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
        execute(f, input);
        try (Fixture restarted = reopen(f)) {
            byte[] alteredManifest = Arrays.copyOf(input.bytes, input.bytes.length + 1);
            alteredManifest[alteredManifest.length - 1] = ' ';
            long events = scalar(restarted.source, "SELECT COUNT(*) FROM rag_release_event");
            assertThrows(RuntimeException.class, () -> RagPublicationRecovery.restore(restarted.lifecycle, restarted.knowledge,
                    input.bytes, "other-operator", ENVIRONMENT, identity(restarted), resources));
            assertThrows(RuntimeException.class, () -> RagPublicationRecovery.restore(restarted.lifecycle, restarted.knowledge,
                    alteredManifest, OPERATOR, ENVIRONMENT, identity(restarted), resources));
            assertThrows(RuntimeException.class, () -> RagPublicationRecovery.restore(restarted.lifecycle, restarted.knowledge,
                    input.bytes, OPERATOR, "other-environment", identity(restarted), resources));
            var wrongArtifact = new PublicationManifest.RuntimeIdentity(BUILD, "c".repeat(64), ENVIRONMENT, restarted.collection,
                    restarted.knowledge.embeddingSignature(), restarted.catalog().epoch(), restarted.store.snapshotHash());
            assertThrows(RuntimeException.class, () -> RagPublicationRecovery.restore(restarted.lifecycle, restarted.knowledge,
                    input.bytes, OPERATOR, ENVIRONMENT, wrongArtifact, resources));
            assertRecoveryRequired(restarted);
            assertEquals(0, restarted.model.calls.get());
            assertEquals(events, scalar(restarted.source, "SELECT COUNT(*) FROM rag_release_event"));
        }
    }

    @Test void changedPhysicalTextCannotRestoreReadAuthority() throws Exception {
        Fixture f = fixture();
        OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
        execute(f, input);
        qdrant("POST", "/collections/" + f.collection + "/points/payload?wait=true",
                Map.of("payload", Map.of("doc_content", "Tampered physical body"),
                        "points", List.of(f.catalog().chunks().getFirst().getId())));
        try (Fixture restarted = reopen(f)) { assertRestoreRejected(restarted, input); }
    }

    @Test void changedFinitePhysicalVectorCannotRestoreReadAuthority() throws Exception {
        Fixture f = fixture();
        OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
        execute(f, input);
        String chunk = f.catalog().chunks().getFirst().getId();
        List<Integer> changed = new ArrayList<>(Collections.nCopies(1024, 0)); changed.set(1, 1);
        qdrant("PUT", "/collections/" + f.collection + "/points/vectors?wait=true",
                Map.of("points", List.of(Map.of("id", chunk, "vector", changed))));
        try (Fixture restarted = reopen(f)) { assertRestoreRejected(restarted, input); }
    }

    @Test void changedCollectionOwnerCannotRestoreReadAuthority() throws Exception {
        Fixture f = fixture();
        OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
        execute(f, input);
        String marker = UUID.nameUUIDFromBytes("chen-rag-index-binding-v1".getBytes(StandardCharsets.UTF_8)).toString();
        qdrant("POST", "/collections/" + f.collection + "/points/payload?wait=true",
                Map.of("payload", Map.of("release_owner", "foreign-owner"), "points", List.of(marker)));
        try (Fixture restarted = reopen(f)) { assertRestoreRejected(restarted, input); }
    }

    @Test void catalogGenerationDriftCannotRestoreOriginalReceipt() throws Exception {
        Fixture f = fixture();
        OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
        execute(f, input);
        f.lifecycle.update(f.catalog().source("help-login"), f.catalog().epoch());
        try (Fixture restarted = reopen(f)) {
            assertEquals(2, restarted.catalog().epoch());
            assertRestoreRejected(restarted, input);
        }
    }

    @Test void metadataOnlySourceDriftCannotCompleteRecoverOrSendEmbedding() throws Exception {
        for (boolean changeKeywords : List.of(true, false)) {
            try (Fixture f = fixture()) {
                execute(f, input(f, PublicationManifest.Operation.PUBLISH, 20, null));
                KnowledgeDocument original = f.catalog().source("help-login");
                KnowledgeDocument changed = new KnowledgeDocument(original.sourceId(), original.title(), original.text(),
                        original.version(), original.sourceType(), original.sourceUrl(), original.visibility(), original.reviewStatus(),
                        changeKeywords ? original.updatedAt() : original.updatedAt().plusSeconds(1),
                        changeKeywords ? List.of("changed-metadata") : original.keywords());
                f.lifecycle.update(changed, f.catalog().epoch());
                OperationInput recover = input(f, PublicationManifest.Operation.RECOVER, 20, null);
                int calls = f.model.calls.get();
                long completed = scalar(f.source, "SELECT COUNT(*) FROM rag_release_event WHERE event_type='COMPLETED'");
                assertThrows(SecurityException.class, () -> execute(f, recover), "Metadata drift must fail before indexing.");
                assertEquals(calls, f.model.calls.get());
                assertNull(f.store.operation(recover.grant.operationId()));
                assertEquals(completed, scalar(f.source, "SELECT COUNT(*) FROM rag_release_event WHERE event_type='COMPLETED'"));
                assertRecoveryRequired(f);
            }
        }
    }

    @Test void legacyCompletedRowWithoutIndexDigestCannotRestore() throws Exception {
        Fixture f = fixture();
        OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
        execute(f, input);
        sql(f.source, "UPDATE rag_release_event SET details_code='INDEX_AUTHORITY_BOUND' WHERE event_type='COMPLETED'");
        try (Fixture restarted = reopen(f)) { assertRestoreRejected(restarted, input); }
    }

    @Test void incompletePublicationCannotUseReadOnlyRecovery() throws Exception {
        Fixture f = fixture();
        OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
        f.model.failAt = 2;
        executeFailure(f, input);
        try (Fixture restarted = reopen(f)) { assertRestoreRejected(restarted, input); }
    }

    @Test void revokedSourceStaysRevokedAcrossReadOnlyRestartRecovery() throws Exception {
        Fixture f = fixture();
        OperationInput original = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
        execute(f, original);
        execute(f, input(f, PublicationManifest.Operation.REVOKE, 0, "help-login"));
        Fixture restarted = reopen(f);
        OperationInput repaired;
        try (restarted) {
            assertRestoreRejected(restarted, original);
            assertEquals("REVOKED", restarted.catalog().source("help-login").reviewStatus());
            repaired = input(restarted, PublicationManifest.Operation.RECOVER, 20, null);
            execute(restarted, repaired);
            assertReady(restarted);
            assertTrue(restarted.knowledge.retrieveByTitle("Synthetic help-login").chunks().isEmpty());
        }
        try (Fixture again = new Fixture(f.source, f.schema, f.collection)) {
            restore(again, repaired);
            assertReady(again);
            assertTrue(again.knowledge.retrieveByTitle("Synthetic help-login").chunks().isEmpty());
            assertEquals(38, again.catalog().chunks().size());
            assertEquals(0, again.model.calls.get());
        }
    }

    @Test void unavailableIndexFailsClosedWithoutEmbeddingOrCatalogWrites() throws Exception {
        Fixture f = fixture();
        OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
        execute(f, input);
        try (Fixture restarted = reopen(f)) {
            long events = scalar(restarted.source, "SELECT COUNT(*) FROM rag_release_event");
            DockerFixture.command("pause", docker.qdrant);
            try { assertThrows(RuntimeException.class, () -> restore(restarted, input)); }
            finally { DockerFixture.command("unpause", docker.qdrant); }
            assertRecoveryRequired(restarted);
            assertEquals(0, restarted.model.calls.get());
            assertEquals(events, scalar(restarted.source, "SELECT COUNT(*) FROM rag_release_event"));
        }
    }

    @Test void unavailableDatabaseFailsClosedWithoutProviderWork() throws Exception {
        Fixture f = fixture();
        OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
        execute(f, input);
        try (Fixture restarted = reopen(f)) {
            PublicationManifest.RuntimeIdentity runtime = identity(restarted);
            DockerFixture.command("pause", docker.mysql);
            try {
                assertThrows(RuntimeException.class, () -> RagPublicationRecovery.restore(restarted.lifecycle, restarted.knowledge,
                        input.bytes, OPERATOR, ENVIRONMENT, runtime, resources));
                assertEquals(0, restarted.model.calls.get());
            } finally { DockerFixture.command("unpause", docker.mysql); }
        }
    }

    @Test void dockerContainerRecreationRetainsCompletedIndexWithoutOperationFileOrEmbedding() throws Exception {
        Fixture f = fixture();
        OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
        execute(f, input);
        long events = scalar(f.source, "SELECT COUNT(*) FROM rag_release_event");
        long requests = f.store.operation(input.grant.operationId()).embeddingRequests();
        List<JsonNode> before = points(f);
        Path operationFile = temporary.resolve("docker-operation-" + UUID.randomUUID() + ".json");
        Files.write(operationFile, input.operationBytes); Files.delete(operationFile);
        f.close();
        docker.recreateQdrant();
        try (Fixture recreated = new Fixture(f.source, f.schema, f.collection)) {
            restore(recreated, input);
            assertReady(recreated);
            assertEquals(0, recreated.model.calls.get());
            assertEquals(requests, recreated.store.operation(input.grant.operationId()).embeddingRequests());
            assertEquals(events, scalar(recreated.source, "SELECT COUNT(*) FROM rag_release_event"));
            assertEquals(before, points(recreated));
            System.out.println("PUBLICATION_IT_DOCKER_RECREATED preservedTaskOwnedVolume=true embeddingCalls=0");
        }
    }

    @Test void partialIndexFailureLeavesRealPointsButNoReadAuthority() throws Exception {
        try (Fixture f = fixture()) {
            f.model.failAt = 2;
            executeFailure(f, input(f, PublicationManifest.Operation.PUBLISH, 20, null));
            assertEquals(11, points(f).size(), "First ten actual Qdrant facts must have committed before injected second-batch failure.");
            assertEquals(1, f.catalog().epoch());
            assertRecoveryRequired(f);
        }
    }

    @Test void partialIndexResumeUsesDurableBudgetAndSingleCatalogApply() throws Exception {
        Fixture f = fixture();
        OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
        f.model.failAt = 2;
        executeFailure(f, input);
        assertEquals(2, f.store.operation(input.grant.operationId()).embeddingRequests());
        Fixture restarted = reopen(f);
        try (restarted) {
            execute(restarted, input);
            assertReady(restarted);
            assertEquals(1, restarted.catalog().epoch());
            assertEquals(6, restarted.store.operation(input.grant.operationId()).embeddingRequests());
            assertEquals(1, scalar(restarted.source, "SELECT COUNT(*) FROM rag_release_event WHERE event_type='CATALOG_APPLIED'"));
            assertEquals(40, points(restarted).size());
        }
    }

    @Test void sqlCommitFailureBeforeCommitRollsBackAndCloses() throws Exception {
        Fixture f = fixture();
        OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
        f.fault.arm(false);
        executeFailure(f, input);
        assertEquals(0, scalar(f.source, "SELECT generation FROM rag_catalog_state WHERE id=1"));
        assertEquals(0, scalar(f.source, "SELECT MAX(generation) FROM rag_release_event WHERE event_type='FAILED'"));
        assertThrows(CatalogLifecycle.Invalidated.class, f.lifecycle::snapshot);
        Fixture restarted = reopen(f);
        try (restarted) {
            execute(restarted, input);
            assertReady(restarted);
            assertEquals(1, restarted.catalog().epoch());
        }
    }

    @Test void sqlCommitThrowsAfterCommitRecoversDurableOutcome() throws Exception {
        Fixture f = fixture();
        OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
        f.fault.arm(true);
        executeFailure(f, input);
        assertEquals(1, scalar(f.source, "SELECT generation FROM rag_catalog_state WHERE id=1"));
        assertEquals(1, scalar(f.source, "SELECT MAX(generation) FROM rag_release_event WHERE event_type='FAILED'"));
        assertThrows(CatalogLifecycle.Invalidated.class, f.lifecycle::snapshot);
        Fixture restarted = reopen(f);
        try (restarted) {
            execute(restarted, input);
            assertReady(restarted);
            assertEquals(1, restarted.catalog().epoch());
            assertEquals(1, scalar(restarted.source, "SELECT COUNT(*) FROM rag_release_event WHERE event_type='CATALOG_APPLIED'"));
        }
    }

    @Test void secondOwnerCannotReadOrPublishWhileFirstLives() throws Exception {
        try (Fixture f = fixture()) {
            assertThrows(IllegalStateException.class, () -> JdbcCatalogStore.open(f.source));
            assertEquals(0, f.catalog().epoch());
            assertUntouched(f);
        }
    }

    @Test void cumulativeOperationInputBudgetCannotResetOnRestart() throws Exception {
        Fixture f = fixture();
        OperationInput input = input(f, PublicationManifest.Operation.PUBLISH, 2, null);
        executeFailure(f, input);
        assertEquals(2, f.model.calls.get());
        Fixture restarted = reopen(f);
        try (restarted) {
            executeFailure(restarted, input);
            assertEquals(0, restarted.model.calls.get());
            assertEquals(2, restarted.store.operation(input.grant.operationId()).embeddingRequests());
            assertRecoveryRequired(restarted);
        }
    }

    @Test void revocationDeleteFailureStillPersistsAndInvalidatesPreviouslyReleasedEvidence() throws Exception {
        try (Fixture f = fixture()) {
            execute(f, input(f, PublicationManifest.Operation.PUBLISH, 20, null));
            KnowledgeEvidence released = f.knowledge.retrieveByTitle("Synthetic help-login");
            assertFalse(released.chunks().isEmpty());
            OperationInput revoke = input(f, PublicationManifest.Operation.REVOKE, 0, "help-login");
            DockerFixture.command("pause", docker.qdrant);
            try { executeFailure(f, revoke); }
            finally { DockerFixture.command("unpause", docker.qdrant); }
            assertEquals("REVOKED", f.catalog().source("help-login").reviewStatus());
            assertThrows(CatalogLifecycle.Invalidated.class, released::context);
            assertRecoveryRequired(f);
            assertTrue(points(f).stream().anyMatch(p -> "help-login".equals(p.path("payload").path("source_id").asText())),
                    "The actual stale physical copy must still exist while logical revocation remains effective.");
            assertEquals("FAILED", f.store.operation(revoke.grant.operationId()).status());
        }
    }

    @Test void recoveryDoesNotReviveRevokedOrExcludedSources() throws Exception {
        try (Fixture f = fixture()) {
            execute(f, input(f, PublicationManifest.Operation.PUBLISH, 20, null));
            execute(f, input(f, PublicationManifest.Operation.REVOKE, 0, "help-login"));
            execute(f, input(f, PublicationManifest.Operation.RECOVER, 20, null));
            assertReady(f);
            assertEquals(2, f.catalog().epoch());
            assertEquals(38, f.catalog().chunks().size());
            for (String excluded : List.of("help-login", "help-history", "help-admin-articles")) {
                assertTrue(f.knowledge.retrieveByTitle("Synthetic " + excluded).chunks().isEmpty());
            }
            assertEquals("REVOKED", f.catalog().source("help-login").reviewStatus());
        }
    }

    @Test void oldPublicationReceiptCannotReplayAfterRevocation() throws Exception {
        try (Fixture f = fixture()) {
            OperationInput old = input(f, PublicationManifest.Operation.PUBLISH, 20, null);
            execute(f, old);
            execute(f, input(f, PublicationManifest.Operation.REVOKE, 0, "help-login"));
            executeFailure(f, old);
            assertEquals("REVOKED", f.catalog().source("help-login").reviewStatus());
            assertEquals(2, f.catalog().epoch());
            assertRecoveryRequired(f);
        }
    }

    @Test void tamperedPhysicalMetadataCannotBecomeEvidence() throws Exception {
        try (Fixture f = fixture()) {
            execute(f, input(f, PublicationManifest.Operation.PUBLISH, 20, null));
            Document chunk = f.catalog().chunks().getFirst();
            qdrant("POST", "/collections/" + f.collection + "/points/payload?wait=true",
                    Map.of("payload", Map.of("content_hash", "forged"), "points", List.of(chunk.getId())));
            String source = (String) chunk.getMetadata().get("source_id");
            RagFailure rejected = assertThrows(RagFailure.class, () -> f.knowledge.retrieveByTitle(f.catalog().source(source).title()));
            assertEquals(RagFailure.Code.INDEX_MISMATCH, rejected.code());
        }
    }

    @Test void unconfirmedWrongOperationAndChangedManifestCannotStartExternalWork() throws Exception {
        try (Fixture f = fixture()) {
            PublicationManifest manifest = manifest(f, PublicationManifest.Operation.PUBLISH, null);
            OperationInput valid = operationInput(manifest, 20);
            var record = (com.fasterxml.jackson.databind.node.ObjectNode) JSON.valueToTree(valid.grant);
            record.put("confirmed", false);
            assertThrows(SecurityException.class, () -> verify(JSON.writeValueAsBytes(record), valid.bytes, manifest.operation()));
            assertThrows(SecurityException.class, () -> verify(valid.operationBytes, valid.bytes, PublicationManifest.Operation.RECOVER));
            byte[] altered = (new String(valid.bytes, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);
            assertThrows(SecurityException.class, () -> verify(valid.operationBytes, altered, manifest.operation()));
            assertUntouched(f);
        }
    }

    @Test void springBootProcessHttpGateTracksRecoveryAndPublication() throws Exception {
        Fixture initial = fixture();
        String schema = initial.schema;
        String collection = initial.collection;
        initial.close(); // The subprocess must acquire the real exclusive owner lock itself.
        try (HttpProcess process = startHttpProcess(schema, collection, "PUBLISH")) {
            JsonNode status = get(process.port, "/it/status");
            assertEquals("RECOVERY_REQUIRED", status.path("before").asText());
            assertEquals("CONFIGURED", status.path("ragAvailability").asText());
            JsonNode evidence = get(process.port, "/it/read?title=Synthetic%20help-login");
            assertEquals("help-login", evidence.path("sourceIds").get(0).asText());
            assertTrue(get(process.port, "/it/read?title=Synthetic%20help-history").path("sourceIds").isEmpty());
            process.process.destroyForcibly();
            assertTrue(process.process.waitFor(10, TimeUnit.SECONDS));
            assertFalse(process.process.isAlive());
            System.out.println("PUBLICATION_IT_HTTP_FORCED_STOP stage=AFTER_COMPLETED_PUBLICATION");
        }
        long events = scalar(docker.source(schema), "SELECT COUNT(*) FROM rag_release_event");
        long requests = scalar(docker.source(schema), "SELECT SUM(embedding_requests) FROM rag_release_operation");
        try (HttpProcess restarted = startHttpProcess(schema, collection, "RESTORE")) {
            assertEquals("CONFIGURED", get(restarted.port, "/it/status").path("ragAvailability").asText());
            assertEquals("help-login", get(restarted.port, "/it/read?title=Synthetic%20help-login").path("sourceIds").get(0).asText());
            assertEquals(0, get(restarted.port, "/it/status").path("embedding").path("requests").asInt());
            assertEquals(events, scalar(docker.source(schema), "SELECT COUNT(*) FROM rag_release_event"));
            assertEquals(requests, scalar(docker.source(schema), "SELECT SUM(embedding_requests) FROM rag_release_operation"));
        }
        System.out.println("PUBLICATION_IT_ARCHIVED_RUNNERS preparedPublication=REAL publicationRunner=REAL readOnlyRecoveryRunner=REAL buildIdentity=REAL embeddingCallsOnRestart=0");
    }

    private record EmptyTarget(DataSource source, String schema, String collection) { }

    private EmptyTarget emptyTarget() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "");
        String schema = "chen_it_" + id;
        DataSource source = docker.source(schema);
        try (Connection connection = docker.source("").getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        }
        applyDdl(source);
        return new EmptyTarget(source, schema, "chen_it_" + id);
    }

    @Test void localOperatorCommandPublishesWithoutHttpServerAndSharesPersistentWireBudget() throws Exception {
        Fixture fixture = fixture();
        String schema = fixture.schema, collection = fixture.collection;
        long generation = fixture.catalog().epoch();
        String snapshot = fixture.store.snapshotHash();
        fixture.close();
        AtomicInteger attempts = new AtomicInteger();
        var provider = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/v1/embeddings", exchange -> {
            attempts.incrementAndGet();
            JsonNode inputs = JSON.readTree(exchange.getRequestBody()).path("input");
            int count = inputs.isArray() ? inputs.size() : 1;
            List<Map<String, Object>> data = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                float[] vector = new float[1024]; vector[0] = 1;
                data.add(Map.of("object", "embedding", "index", i, "embedding", vector));
            }
            byte[] response = JSON.writeValueAsBytes(Map.of("object", "list", "data", data, "model", "synthetic",
                    "usage", Map.of("prompt_tokens", count, "total_tokens", count)));
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        provider.start();
        try {
            Path artifact = syntheticRuntimeJar();
            Path control = Files.createDirectory(temporary.resolve("command-" + schema));
            String baseUrl = "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1";
            Map<String, String> hashes = new TreeMap<>(); resources.forEach((name, bytes) -> hashes.put(name, PublicationManifest.sha256(bytes)));
            PublicationManifest manifest = new PublicationManifest(1, BUILD, PublicationManifest.sha256(Files.readAllBytes(artifact)),
                    ENVIRONMENT, collection, KnowledgeCatalog.hash(baseUrl + "|synthetic-vector-1024|1024|chunk-v1"), hashes,
                    generation, snapshot, PublicationManifest.Operation.PUBLISH, null, false, true,
                    drafts.stream().filter(d -> PublicationManifest.userSourceIds().contains(d.sourceId()))
                            .map(d -> new PublicationManifest.Topic(d.sourceId(), d.version(), KnowledgeCatalog.hash(d.text()))).toList());
            OperationInput input = operationInput(manifest, 20);
            Files.write(control.resolve("manifest.json"), input.bytes); Files.write(control.resolve("operation.json"), input.operationBytes);
            Path ledger = control.resolve("provider-attempts.ledger");
            Files.writeString(ledger, "CHEN_RELEASE_HTTP_BUDGET_V1|it-wire|64|20\n");
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            String dependencies = Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator)))
                    .filter(path -> path.endsWith(".jar") && Files.isRegularFile(Path.of(path)))
                    .collect(java.util.stream.Collectors.joining(File.pathSeparator));
            List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", artifact + File.pathSeparator + dependencies, RagPublicationCommand.class.getName(),
                    "--spring.config.name=chen-release-isolated-test", "--spring.profiles.active=release-it",
                    "--spring.datasource.url=jdbc:mysql://127.0.0.1:" + docker.mysqlPort + "/" + schema + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC",
                    "--spring.datasource.username=root", "--chen.rag.enabled=true", "--chen.rag.collection=" + collection,
                    "--chen.rag.qdrant-grpc-port=" + docker.grpcPort, "--chen.rag.embedding-base-url=" + baseUrl,
                    "--chen.rag.embedding-model=synthetic-vector-1024", "--chen.rag.embedding-api-key=synthetic-not-real",
                    "--chen.rag.release.enabled=true", "--chen.rag.release.recovery-enabled=false",
                    "--chen.rag.release.manifest-path=" + control.resolve("manifest.json"),
                    "--chen.rag.release.operation-path=" + control.resolve("operation.json"),
                    "--chen.rag.release.artifact-path=" + artifact, "--chen.rag.release.operator=" + OPERATOR,
                    "--chen.rag.release.environment=" + ENVIRONMENT, "--chen.rag.release.budget-ledger-path=" + ledger,
                    "--chen.rag.release.budget-id=it-wire"));
            Path log = control.resolve("command.log");
            var builder = new ProcessBuilder(javaArgumentFile(command)).directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().clear();
            builder.environment().put("SystemRoot", System.getenv("SystemRoot"));
            builder.environment().put("TEMP", temporary.toString()); builder.environment().put("TMP", temporary.toString());
            builder.environment().put("SPRING_DATASOURCE_PASSWORD", docker.password);
            Process process = builder.start();
            if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Local operator command did not exit."); }
            assertEquals(0, process.exitValue(), () -> readSanitizedCommandLog(log));
            assertTrue(attempts.get() > 0 && attempts.get() <= 20);
            assertEquals(attempts.get(), new ReleaseProviderBudget(ledger.toString(), "it-wire", 64, 20).snapshot().get("embedding"));
            assertEquals(39, scalar(docker.source(schema), "SELECT COUNT(*) FROM rag_source_state WHERE JSON_UNQUOTE(JSON_EXTRACT(document_json,'$.reviewStatus'))='DOCUMENT_REVIEWED'"));
            assertEquals(1, scalar(docker.source(schema), "SELECT COUNT(*) FROM rag_release_operation WHERE status='COMPLETED' AND approver_id='CONTROLLED_OPERATOR'"));
            int spent = attempts.get();
            command.add("--spring.main.web-application-type=servlet");
            process = builder.command(javaArgumentFile(command)).start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Web-mode rejection did not exit."); }
            assertNotEquals(0, process.exitValue()); assertEquals(spent, attempts.get());
            System.out.println("PUBLICATION_IT_LOCAL_COMMAND nonWeb=true durableWireBudget=true productionConfiguration=REAL externalProviderCalls=0");
        } finally { provider.stop(0); }
    }

    private String readSanitizedCommandLog(Path path) {
        try {
            String text = Files.readString(path).replace(docker.password, "[SYNTHETIC_PASSWORD_REDACTED]");
            return text.substring(Math.max(0, text.length() - 6000));
        } catch (IOException error) { return "COMMAND_LOG_UNAVAILABLE"; }
    }

    @Test void incrementalUpdatePreservesExistingVectorsAndRestartsWithoutEmbedding() throws Exception {
        List<KnowledgeDocument> originalDrafts = drafts;
        Map<String, byte[]> originalResources = resources;
        try (Fixture f = fixture()) {
            execute(f, input(f, PublicationManifest.Operation.PUBLISH, 20, null));
            List<Document> retained = f.catalog().chunks().stream()
                    .filter(d -> !"help-article-manage".equals(d.getMetadata().get("source_id"))).toList();
            List<String> ids = retained.stream().map(Document::getId).toList();
            JsonNode vectors = qdrant("POST", "/collections/" + f.collection + "/points",
                    Map.of("ids", ids, "with_vector", true, "with_payload", true)).path("result");
            Map<String, JsonNode> oldVectors = new HashMap<>();
            vectors.forEach(point -> oldVectors.put(point.path("id").asText(), point.path("vector")));
            int calls = f.model.calls.get();
            reviseArticleDraft("review-v2");
            OperationInput update = input(f, PublicationManifest.Operation.UPDATE, 1, null);
            assertEquals("COMPLETED", execute(f, update).get("status"));
            assertEquals(calls + 1, f.model.calls.get());
            assertEquals(2, f.catalog().epoch());
            assertEquals(39, f.catalog().documents().stream().filter(d -> "PUBLIC".equals(d.visibility())).count());
            assertEquals(1, f.store.operation(update.grant.operationId()).embeddingRequests());
            assertTrue(f.knowledge.retrieveByTitle("Synthetic help-article-manage").context().contains("article review revision"));
            JsonNode after = qdrant("POST", "/collections/" + f.collection + "/points",
                    Map.of("ids", ids, "with_vector", true, "with_payload", true)).path("result");
            assertEquals(ids.size(), after.size());
            after.forEach(point -> {
                assertEquals(oldVectors.get(point.path("id").asText()), point.path("vector"));
                assertEquals("2", point.path("payload").path("catalog_epoch").asText());
            });
            assertEquals("COMPLETED", execute(f, update).get("status"));
            assertEquals(calls + 1, f.model.calls.get());
            long events = scalar(f.source, "SELECT COUNT(*) FROM rag_release_event");
            try (Fixture restarted = reopen(f)) {
                assertEquals("READ_ONLY_RECOVERED", restore(restarted, update).get("status"));
                assertReady(restarted);
                assertTrue(restarted.knowledge.retrieveByTitle("Synthetic help-article-manage").context().contains("article review revision"));
                assertEquals(0, restarted.model.calls.get());
                assertEquals(events, scalar(restarted.source, "SELECT COUNT(*) FROM rag_release_event"));
            }
        } finally { drafts = originalDrafts; resources = originalResources; }
    }

    @Test void incrementalUpdateRejectsNoChangeSameVersionDriftAndDamagedIndexBeforeEmbedding() throws Exception {
        List<KnowledgeDocument> originalDrafts = drafts;
        Map<String, byte[]> originalResources = resources;
        try (Fixture f = fixture()) {
            execute(f, input(f, PublicationManifest.Operation.PUBLISH, 20, null));
            int calls = f.model.calls.get();
            long events = scalar(f.source, "SELECT COUNT(*) FROM rag_release_event");
            assertThrows(SecurityException.class, () -> execute(f, input(f, PublicationManifest.Operation.UPDATE, 1, null)));
            reviseArticleDraft(originalDrafts.stream().filter(d -> "help-article-manage".equals(d.sourceId())).findFirst().orElseThrow().version());
            assertThrows(SecurityException.class, () -> execute(f, input(f, PublicationManifest.Operation.UPDATE, 1, null)));
            drafts = originalDrafts; resources = originalResources;
            reviseArticleDraft("review-v2");
            String point = f.catalog().chunks().getFirst().getId();
            qdrant("POST", "/collections/" + f.collection + "/points/payload?wait=true",
                    Map.of("points", List.of(point), "payload", Map.of("doc_content", "tampered")));
            assertThrows(RuntimeException.class, () -> execute(f, input(f, PublicationManifest.Operation.UPDATE, 1, null)));
            assertEquals(1, f.catalog().epoch());
            assertEquals(calls, f.model.calls.get());
            assertEquals(events, scalar(f.source, "SELECT COUNT(*) FROM rag_release_event"));
            assertFalse(f.knowledge.publicationReady());
        } finally { drafts = originalDrafts; resources = originalResources; }
    }

    @Test void incrementalUpdateFailureRemainsClosedAndCannotAutomaticallyReplayAppliedCatalog() throws Exception {
        List<KnowledgeDocument> originalDrafts = drafts;
        Map<String, byte[]> originalResources = resources;
        try (Fixture f = fixture()) {
            execute(f, input(f, PublicationManifest.Operation.PUBLISH, 20, null));
            reviseArticleDraft("review-v2");
            OperationInput update = input(f, PublicationManifest.Operation.UPDATE, 1, null);
            f.model.failAt = f.model.calls.get() + 1;
            assertThrows(RuntimeException.class, () -> execute(f, update));
            assertEquals(2, f.catalog().epoch());
            assertFalse(f.knowledge.publicationReady());
            int calls = f.model.calls.get();
            assertThrows(SecurityException.class, () -> execute(f, update));
            assertEquals(calls, f.model.calls.get());
            assertEquals(1, f.store.operation(update.grant.operationId()).embeddingRequests());
            assertFalse(f.knowledge.publicationReady());
        } finally { drafts = originalDrafts; resources = originalResources; }
    }

    private void reviseArticleDraft(String version) throws Exception {
        drafts = drafts.stream().map(d -> "help-article-manage".equals(d.sourceId())
                ? new KnowledgeDocument(d.sourceId(), d.title(), d.text() + " article review revision", version,
                    d.sourceType(), d.sourceUrl(), d.visibility(), d.reviewStatus(), d.updatedAt(), d.keywords()) : d).toList();
        resources = Map.of(PublicationManifest.PLATFORM_HELP_RESOURCE, JSON.writeValueAsBytes(drafts),
                PublicationManifest.CAPABILITIES_RESOURCE, resources.get(PublicationManifest.CAPABILITIES_RESOURCE));
    }

    private Fixture fixture() throws Exception {
        EmptyTarget target = emptyTarget();
        JdbcCatalogStore.initialize(target.source, drafts);
        return new Fixture(target.source, target.schema, target.collection);
    }

    private Fixture reopen(Fixture old) throws Exception {
        old.close();
        return new Fixture(old.source, old.schema, old.collection);
    }

    private final class Fixture implements AutoCloseable {
        final DataSource source;
        final String schema;
        final String collection;
        final CommitFault fault;
        final JdbcCatalogStore store;
        final CatalogLifecycle lifecycle;
        final LocalEmbedding model = new LocalEmbedding();
        final RagKnowledgeService knowledge;
        private boolean closed;

        Fixture(DataSource source, String schema, String collection) {
            this(source, schema, collection, null);
        }

        Fixture(DataSource source, String schema, String collection, KnowledgeCatalog opened) {
            this.source = source; this.schema = schema; this.collection = collection;
            fault = new CommitFault(source);
            store = opened == null ? JdbcCatalogStore.open(fault) : opened.lifecycle().store();
            lifecycle = opened == null ? KnowledgeCatalog.restore(store).lifecycle() : opened.lifecycle();
            RagProperties properties = properties(collection, docker.grpcPort);
            GuardedEmbeddingModel embedding = new GuardedEmbeddingModel(model, properties);
            knowledge = new RagKnowledgeService(properties, lifecycle, new QdrantKnowledgeIndex(properties, embedding), embedding);
        }

        KnowledgeCatalog catalog() { return lifecycle.snapshot(); }
        @Override public void close() {
            if (closed) return;
            closed = true;
            try { knowledge.close(); } finally { lifecycle.close(); }
        }
    }

    private record OperationInput(PublicationManifest manifest, byte[] bytes, byte[] operationBytes,
                            PublicationAuthorization.Grant grant, List<KnowledgeDocument> validatedDrafts) { }

    private OperationInput input(Fixture f, PublicationManifest.Operation operation, int budget, String revoke) throws Exception {
        PublicationManifest manifest = manifest(f, operation, revoke);
        OperationInput confirmed = operationInput(manifest, budget);
        return new OperationInput(manifest, confirmed.bytes, confirmed.operationBytes, confirmed.grant, manifest.validate(identity(f), resources));
    }

    private OperationInput zeroEmbeddingUpgrade(Fixture f) throws Exception {
        PublicationManifest base = manifest(f, PublicationManifest.Operation.RECOVER, null);
        PublicationManifest upgraded = new PublicationManifest(base.schemaVersion(), "c".repeat(40), "d".repeat(64),
                base.environment(), base.collection(), base.embeddingSignature(), base.resourceSha256(), base.expectedGeneration(),
                base.expectedSnapshotSha256(), base.operation(), null, false, false, base.topics());
        OperationInput confirmed = operationInput(upgraded, 0);
        return new OperationInput(upgraded, confirmed.bytes, confirmed.operationBytes, confirmed.grant,
                upgraded.validate(upgradeIdentity(f, confirmed), resources));
    }

    private PublicationManifest.RuntimeIdentity upgradeIdentity(Fixture f, OperationInput upgrade) {
        return new PublicationManifest.RuntimeIdentity(upgrade.manifest.gitCommit(), upgrade.manifest.artifactSha256(),
                ENVIRONMENT, f.collection, f.knowledge.embeddingSignature(), f.catalog().epoch(), f.store.snapshotHash());
    }

    private PublicationManifest manifest(Fixture f, PublicationManifest.Operation operation, String revoke) {
        Map<String, String> hashes = new TreeMap<>();
        resources.forEach((key, value) -> hashes.put(key, PublicationManifest.sha256(value)));
        List<PublicationManifest.Topic> topics = drafts.stream().filter(d -> PublicationManifest.userSourceIds().contains(d.sourceId()))
                .map(d -> new PublicationManifest.Topic(d.sourceId(), d.version(), KnowledgeCatalog.hash(KnowledgeCatalog.normalize(d.text())))).toList();
        return new PublicationManifest(1, BUILD, ARTIFACT, ENVIRONMENT, f.collection, f.knowledge.embeddingSignature(),
                hashes, f.catalog().epoch(), f.store.snapshotHash(), operation, revoke, false,
                operation == PublicationManifest.Operation.PUBLISH, topics);
    }

    private PublicationManifest.RuntimeIdentity identity(Fixture f) {
        return new PublicationManifest.RuntimeIdentity(BUILD, ARTIFACT, ENVIRONMENT, f.collection,
                f.knowledge.embeddingSignature(), f.catalog().epoch(), f.store.snapshotHash());
    }

    private PublicationManifest withTopics(PublicationManifest m, List<PublicationManifest.Topic> topics) {
        return new PublicationManifest(m.schemaVersion(), m.gitCommit(), m.artifactSha256(), m.environment(), m.collection(),
                m.embeddingSignature(), m.resourceSha256(), m.expectedGeneration(), m.expectedSnapshotSha256(),
                m.operation(), m.revokeSourceId(), m.initializeEmptyCatalog(), m.createCollection(), topics);
    }

    private OperationInput operationInput(PublicationManifest manifest, int budget) throws Exception {
        byte[] bytes = JSON.writeValueAsBytes(manifest);
        var grant = new PublicationAuthorization.Grant(1, "it_" + UUID.randomUUID().toString().replace("-", ""),
                OPERATOR, ENVIRONMENT, PublicationManifest.sha256(bytes), budget, manifest.operation(), true);
        byte[] operationBytes = JSON.writeValueAsBytes(grant);
        return new OperationInput(manifest, bytes, operationBytes, verify(operationBytes, bytes, manifest.operation()), List.of());
    }

    private PublicationAuthorization.Grant verify(byte[] operationBytes, byte[] manifest, PublicationManifest.Operation operation) {
        return PublicationAuthorization.verify(operationBytes, manifest, OPERATOR, ENVIRONMENT, operation);
    }

    private Map<String, Object> execute(Fixture f, OperationInput input) {
        return new RagPublicationExecutor(f.lifecycle, f.knowledge, input.manifest, input.grant,
                PublicationManifest.sha256(input.operationBytes), input.validatedDrafts).execute();
    }

    private Map<String, Object> restore(Fixture f, OperationInput input) {
        return RagPublicationRecovery.restore(f.lifecycle, f.knowledge, input.bytes, OPERATOR, ENVIRONMENT, identity(f), resources);
    }

    private void assertRestoreRejected(Fixture f, OperationInput input) throws Exception {
        long events = scalar(f.source, "SELECT COUNT(*) FROM rag_release_event");
        long requests = scalar(f.source, "SELECT COALESCE(SUM(embedding_requests),0) FROM rag_release_operation");
        assertThrows(RuntimeException.class, () -> restore(f, input));
        assertRecoveryRequired(f);
        assertEquals(0, f.model.calls.get());
        assertEquals(events, scalar(f.source, "SELECT COUNT(*) FROM rag_release_event"));
        assertEquals(requests, scalar(f.source, "SELECT COALESCE(SUM(embedding_requests),0) FROM rag_release_operation"));
    }

    private void executeFailure(Fixture f, OperationInput input) {
        try { execute(f, input); }
        catch (RuntimeException expected) { return; }
        assertFalse(f.knowledge.publicationReady(), "A failure must never leave readable publication authority.");
    }

    private void assertReady(Fixture f) {
        assertTrue(f.knowledge.publicationReady());
        assertEquals("CONFIGURED", f.knowledge.diagnostics().get("ragAvailability"));
        assertFalse(f.knowledge.retrieveByTitle("Synthetic help-register").chunks().isEmpty());
    }

    private void assertRecoveryRequired(Fixture f) {
        assertFalse(f.knowledge.publicationReady());
        assertEquals("RECOVERY_REQUIRED", f.knowledge.diagnostics().get("ragAvailability"));
        assertEquals(RagFailure.Code.RECOVERY_REQUIRED,
                assertThrows(RagFailure.class, () -> f.knowledge.retrieveByTitle("Synthetic help-login")).code());
    }

    private void assertUntouched(Fixture f) throws Exception {
        assertEquals(0, f.catalog().epoch());
        assertEquals(0, f.model.calls.get());
        assertEquals(0, scalar(f.source, "SELECT COUNT(*) FROM rag_release_operation"));
        assertFalse(f.knowledge.publicationReady());
        assertFalse(qdrant("GET", "/collections", null).path("result").path("collections").findValuesAsText("name").contains(f.collection));
    }

    private static List<KnowledgeDocument> syntheticDrafts() throws Exception {
        try (var input = PublicationIntegrationIT.class.getClassLoader().getResourceAsStream(PublicationManifest.PLATFORM_HELP_RESOURCE)) {
            assertNotNull(input);
            KnowledgeDocument[] originals = JSON.readValue(input, KnowledgeDocument[].class);
            assertEquals(52, originals.length);
            return Arrays.stream(originals).map(d -> new KnowledgeDocument(d.sourceId(), "Synthetic " + d.sourceId(),
                    "Isolated synthetic fact for " + d.sourceId() + ". This fixture contains no business data.",
                    "synthetic-1", "PLATFORM_HELP", "/", "NOT_PUBLISHED", "CONTENT_REVIEW_REQUIRED", Instant.EPOCH, List.of("synthetic"))).toList();
        }
    }

    private static RagProperties properties(String collection, int port) {
        RagProperties properties = new RagProperties();
        properties.setEnabled(true); properties.setCollection(collection); properties.setQdrantGrpcPort(port);
        properties.setEmbeddingBaseUrl("http://127.0.0.1/synthetic-no-http-calls");
        properties.setEmbeddingModel("synthetic-vector-1024"); properties.setMaxEmbeddingRequestsPerDay(100_000);
        return properties;
    }

    private static long scalar(DataSource source, String sql) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement(); var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); return rows.getLong(1);
        }
    }

    private static void sql(DataSource source, String sql) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.createStatement()) { statement.executeUpdate(sql); }
    }

    private static void applyDdl(DataSource source) throws Exception {
        Path cursor = Path.of("").toAbsolutePath();
        Path sql = null;
        for (int depth = 0; cursor != null && depth < 8; depth++, cursor = cursor.getParent()) {
            Path candidate = cursor.resolve("sql/g2_rag_catalog.sql");
            if (Files.isRegularFile(candidate)) { sql = candidate; break; }
        }
        assertNotNull(sql, "Run the integration test inside the source repository containing the reviewed formal DDL.");
        String ddl = Files.readString(sql, StandardCharsets.UTF_8).replaceAll("(?m)^\\s*--.*$", "");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            for (String command : ddl.split(";")) if (!command.isBlank()) statement.execute(command);
        }
    }

    private List<JsonNode> points(Fixture f) throws Exception {
        JsonNode result = qdrant("POST", "/collections/" + f.collection + "/points/scroll",
                Map.of("limit", 1000, "with_payload", true, "with_vector", false));
        List<JsonNode> points = new ArrayList<>(); result.path("result").path("points").forEach(points::add); return points;
    }

    private JsonNode qdrant(String method, String path, Object body) throws Exception {
        return request("http://127.0.0.1:" + docker.restPort + path, method, body);
    }

    private static JsonNode request(String url, String method, Object body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10));
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)));
        if (body != null) builder.header("Content-Type", "application/json");
        var response = HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, "HTTP status " + response.statusCode());
        return JSON.readTree(response.body());
    }

    private static JsonNode get(int port, String path) throws Exception { return request("http://127.0.0.1:" + port + path, "GET", null); }

    /** A deterministic local provider, with a failure before a selected batch reaches Qdrant. */
    private static final class LocalEmbedding extends AbstractEmbeddingModel {
        final AtomicInteger calls = new AtomicInteger();
        volatile int failAt = -1;
        volatile CountDownLatch entered, proceed;
        @Override public int dimensions() { return 1024; }
        @Override public float[] embed(Document document) { return embed(document.getText()); }
        @Override public EmbeddingResponse call(EmbeddingRequest request) {
            if (entered != null && proceed != null) {
                entered.countDown();
                try { if (!proceed.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("SYNTHETIC_TEST_BARRIER_TIMEOUT"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("SYNTHETIC_TEST_INTERRUPTED"); }
            }
            if (calls.incrementAndGet() == failAt) throw new IllegalStateException("SYNTHETIC_EMBEDDING_FAILURE");
            return new EmbeddingResponse(IntStream.range(0, request.getInstructions().size()).mapToObj(i -> {
                float[] vector = new float[1024]; vector[0] = 1; return new Embedding(vector, i);
            }).toList());
        }
    }

    /** Wraps a real JDBC connection; only the actual Catalog transaction commit is faulted once. */
    private static final class CommitFault extends AbstractDataSource {
        final DataSource delegate;
        volatile boolean armed;
        volatile boolean after;
        CommitFault(DataSource delegate) { this.delegate = delegate; }
        void arm(boolean after) { this.after = after; armed = true; }
        @Override public Connection getConnection() throws SQLException { return wrap(delegate.getConnection()); }
        @Override public Connection getConnection(String user, String password) throws SQLException { return wrap(delegate.getConnection(user, password)); }
        private Connection wrap(Connection connection) {
            boolean[] catalogWritten = {false};
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class[]{Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("prepareStatement") && args[0] instanceof String sql && sql.startsWith("UPDATE rag_catalog_state SET")) catalogWritten[0] = true;
                if (method.getName().equals("commit") && armed && catalogWritten[0]) {
                    armed = false; catalogWritten[0] = false;
                    if (after) connection.commit();
                    throw new SQLException(after ? "SYNTHETIC_AFTER_COMMIT" : "SYNTHETIC_BEFORE_COMMIT");
                }
                try { return method.invoke(connection, args); }
                catch (InvocationTargetException exception) { throw exception.getCause(); }
                finally { if (method.getName().equals("rollback")) catalogWritten[0] = false; }
            });
        }
    }

    private static final class DockerFixture implements AutoCloseable {
        final String id = UUID.randomUUID().toString().replace("-", "");
        final String network = "chen-release-it-" + id;
        final String mysql = network + "-mysql";
        final String qdrant = network + "-qdrant";
        final String volume = network + "-qdrant-data";
        final String password = UUID.randomUUID().toString();
        final Path temporary;
        int mysqlPort, grpcPort, restPort;
        DockerFixture(Path temporary) { this.temporary = temporary; }
        void start() throws Exception {
            // Explicit no-pull prevents accidental network access or dependency drift.
            command("image", "inspect", "mysql:8.0"); command("image", "inspect", "qdrant/qdrant:v1.13.6");
            try {
                command("network", "create", "--driver", "bridge", "--label", "chen.release.it=" + id, network);
                command("volume", "create", "--label", "chen.release.it=" + id, volume);
                Path env = temporary.resolve("mysql.env");
                Files.writeString(env, "MYSQL_ROOT_PASSWORD=" + password + "\nMYSQL_ROOT_HOST=%\n", StandardCharsets.UTF_8);
                command("run", "--pull=never", "--detach", "--name", mysql, "--label", "chen.release.it=" + id,
                        "--network", network, "--dns", "127.0.0.1", "--publish", "127.0.0.1::3306", "--env-file", env.toString(), "mysql:8.0");
                startQdrant();
                mysqlPort = port(mysql, "3306/tcp"); restPort = port(qdrant, "6333/tcp"); grpcPort = port(qdrant, "6334/tcp");
                long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
                boolean ready = false;
                while (System.nanoTime() < deadline) {
                    try (var connection = source("").getConnection()) {
                        request("http://127.0.0.1:" + restPort + "/collections", "GET", null); ready = true; break;
                    } catch (Exception ignored) { Thread.sleep(500); }
                }
                assertTrue(ready, "New task-owned MySQL/Qdrant did not become ready.");
                System.out.println("PUBLICATION_IT_INFRASTRUCTURE label=" + id + " dedicatedBridge=" + network
                        + " mysqlImage=" + command("image", "inspect", "--format", "{{.Id}}", "mysql:8.0").strip()
                        + " qdrantImage=" + command("image", "inspect", "--format", "{{.Id}}", "qdrant/qdrant:v1.13.6").strip());
            } catch (Exception | AssertionError error) {
                try { close(); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
                throw error;
            }
        }
        DataSource source(String schema) {
            DriverManagerDataSource source = new DriverManagerDataSource();
            source.setDriverClassName("com.mysql.cj.jdbc.Driver");
            source.setUrl("jdbc:mysql://127.0.0.1:" + mysqlPort + "/" + schema + "?allowPublicKeyRetrieval=true&useSSL=false&connectTimeout=2000&socketTimeout=5000&serverTimezone=UTC");
            source.setUsername("root"); source.setPassword(password); return source;
        }
        void startQdrant() throws Exception {
            command("run", "--pull=never", "--detach", "--name", qdrant, "--label", "chen.release.it=" + id,
                    "--network", network, "--dns", "127.0.0.1", "--env", "QDRANT__TELEMETRY_DISABLED=true",
                    "--mount", "type=volume,src=" + volume + ",dst=/qdrant/storage",
                    "--publish", "127.0.0.1:" + (restPort == 0 ? "" : restPort) + ":6333",
                    "--publish", "127.0.0.1:" + (grpcPort == 0 ? "" : grpcPort) + ":6334", "qdrant/qdrant:v1.13.6");
        }
        void recreateQdrant() throws Exception {
            assertEquals(id, JSON.readTree(command("inspect", "--format", "{{json .Config.Labels}}", qdrant)).path("chen.release.it").asText());
            assertEquals(id, JSON.readTree(command("volume", "inspect", "--format", "{{json .Labels}}", volume)).path("chen.release.it").asText());
            command("stop", "--time", "10", qdrant);
            command("rm", qdrant);
            startQdrant();
            long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            boolean ready = false;
            while (System.nanoTime() < deadline) {
                try { request("http://127.0.0.1:" + restPort + "/collections", "GET", null); ready = true; break; }
                catch (Exception ignored) { Thread.sleep(200); }
            }
            assertTrue(ready, "Recreated task-owned Qdrant must retain its volume and become available.");
        }
        int port(String container, String privatePort) throws Exception {
            String output = command("port", container, privatePort).strip(); return Integer.parseInt(output.substring(output.lastIndexOf(':') + 1));
        }
        static String command(String... arguments) throws Exception {
            List<String> args = new ArrayList<>(); args.add("docker"); args.addAll(List.of(arguments));
            Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(45, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IOException("TASK_DOCKER_TIMEOUT"); }
            if (process.exitValue() != 0) throw new IOException("TASK_DOCKER_FAILED: " + output.substring(0, Math.min(output.length(), 500)));
            return output;
        }
        @Override public void close() throws Exception {
            Set<String> owned = new HashSet<>(command("ps", "--all", "--filter", "label=chen.release.it=" + id,
                    "--format", "{{.Names}}").lines().toList());
            for (String container : List.of(mysql, qdrant)) {
                if (!owned.contains(container)) continue;
                assertEquals(id, JSON.readTree(command("inspect", "--format", "{{json .Config.Labels}}", container)).path("chen.release.it").asText());
                command("rm", "--force", "--volumes", container);
            }
            Set<String> ownedNetworks = new HashSet<>(command("network", "ls", "--filter", "label=chen.release.it=" + id,
                    "--format", "{{.Name}}").lines().toList());
            if (ownedNetworks.contains(network)) {
                assertEquals(id, JSON.readTree(command("network", "inspect", "--format", "{{json .Labels}}", network)).path("chen.release.it").asText());
                command("network", "rm", network);
            }
            Set<String> ownedVolumes = new HashSet<>(command("volume", "ls", "--filter", "label=chen.release.it=" + id,
                    "--format", "{{.Name}}").lines().toList());
            if (ownedVolumes.contains(volume)) {
                assertEquals(id, JSON.readTree(command("volume", "inspect", "--format", "{{json .Labels}}", volume)).path("chen.release.it").asText());
                command("volume", "rm", volume);
            }
            assertTrue(command("ps", "--all", "--filter", "label=chen.release.it=" + id, "--format", "{{.Names}}").isBlank());
            assertTrue(command("network", "ls", "--filter", "label=chen.release.it=" + id, "--format", "{{.Name}}").isBlank());
            assertTrue(command("volume", "ls", "--filter", "label=chen.release.it=" + id, "--format", "{{.Name}}").isBlank());
            System.out.println("PUBLICATION_IT_CLEANED label=" + id);
        }
    }

    private HttpProcess startHttpProcess(String schema, String collection, String action) throws Exception {
        Path marker = temporary.resolve("http-" + UUID.randomUUID() + ".port");
        Path log = temporary.resolve("http-" + UUID.randomUUID() + ".log");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        String dependencies = Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator)))
                .filter(path -> path.endsWith(".jar") && Files.isRegularFile(Path.of(path)))
                .collect(java.util.stream.Collectors.joining(File.pathSeparator));
        assertFalse(dependencies.isBlank(), "The isolated archive must retain the existing Surefire dependency JARs.");
        Path artifact = syntheticRuntimeJar();
        var builder = new ProcessBuilder(javaArgumentFile(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                artifact + File.pathSeparator + dependencies,
                HttpApplication.class.getName(), Integer.toString(docker.mysqlPort), Integer.toString(docker.grpcPort), schema,
                collection, action, marker.toString(), temporary.resolve("receipt-" + schema).toString(), artifact.toString())))
                .directory(temporary.toFile());
        builder.environment().clear();
        builder.environment().put("SystemRoot", System.getenv("SystemRoot"));
        builder.environment().put("TEMP", temporary.toString()); builder.environment().put("TMP", temporary.toString());
        builder.environment().put("CHEN_SYNTHETIC_MYSQL_PASSWORD", docker.password);
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = builder.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (process.isAlive() && !Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(100);
        if (!Files.exists(marker)) {
            process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            String details = Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "NO_LOG";
            fail("Synthetic Spring Boot process did not start: " + details.substring(Math.max(0, details.length() - 8000)));
        }
        return new HttpProcess(process, Integer.parseInt(Files.readString(marker).strip()));
    }

    /** JDK argument files avoid Windows' command-line limit as the managed classpath grows. */
    private List<String> javaArgumentFile(List<String> command) throws IOException {
        Path arguments = Files.createTempFile(temporary, "java-", ".args");
        String content = "\"-Dfile.encoding=UTF-8\"\n\"-Djava.io.tmpdir=" + temporary.toString().replace("\\", "\\\\") + "\"\n"
                + command.subList(1, command.size()).stream()
                .map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining("\n"));
        Files.writeString(arguments, content, StandardCharsets.UTF_8);
        return List.of(command.getFirst(), "@" + arguments);
    }

    /** Test archive contains compiled classes and synthetic public resources, never application config or private keys. */
    private Path syntheticRuntimeJar() throws Exception {
        if (runtimeJar != null) return runtimeJar;
        Path artifact = temporary.resolve("synthetic-release-runtime.jar");
        Set<String> entries = new HashSet<>();
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(artifact))) {
            for (Class<?> type : List.of(PublicationBuildIdentity.class, PublicationIntegrationIT.class)) {
                Path root = Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
                assertTrue(Files.isDirectory(root), "Only official compiled classes are packaged into the synthetic archive.");
                try (var files = Files.walk(root)) {
                    for (Path file : files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".class")).sorted().toList()) {
                        String name = root.relativize(file).toString().replace(File.separatorChar, '/');
                        if (!entries.add(name)) continue;
                        jar.putNextEntry(new JarEntry(name)); Files.copy(file, jar); jar.closeEntry();
                    }
                }
            }
            Map<String, byte[]> publicResources = new TreeMap<>(resources);
            publicResources.put("META-INF/chen-release-build.properties", ("gitCommit=" + BUILD + "\n").getBytes(StandardCharsets.UTF_8));
            for (var resource : publicResources.entrySet()) {
                assertTrue(entries.add(resource.getKey()));
                jar.putNextEntry(new JarEntry(resource.getKey())); jar.write(resource.getValue()); jar.closeEntry();
            }
        }
        runtimeJar = artifact;
        return artifact;
    }

    private record HttpProcess(Process process, int port) implements AutoCloseable {
        @Override public void close() throws Exception {
            process.destroy(); if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
        }
    }

    /** A separate real servlet JVM; only the HTTP observation surface is test-specific. */
    @Configuration(proxyBeanMethods = false)
    @Import({TomcatServletWebServerAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, Jackson2AutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
    public static class HttpApplication {
        public static void main(String[] args) throws Exception {
            int mysqlPort = Integer.parseInt(args[0]), grpcPort = Integer.parseInt(args[1]);
            DriverManagerDataSource source = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + mysqlPort + "/" + args[2]
                    + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC", "root", System.getenv("CHEN_SYNTHETIC_MYSQL_PASSWORD"));
            JdbcCatalogStore store = JdbcCatalogStore.open(source);
            KnowledgeCatalog catalog = KnowledgeCatalog.restore(store);
            RagProperties properties = properties(args[3], grpcPort);
            properties.setEmbeddingApiKey("synthetic-local-provider-only");
            GuardedEmbeddingModel embedding = new GuardedEmbeddingModel(new LocalEmbedding(), properties);
            RagKnowledgeService knowledge = new RagKnowledgeService(properties, catalog, new QdrantKnowledgeIndex(properties, embedding), embedding);
            String before = (String) knowledge.diagnostics().get("ragAvailability");
            Path receipt = Path.of(args[6]);
            PublicationBuildIdentity.Identity build = PublicationBuildIdentity.read(Path.of(args[7]));
            assertEquals(BUILD, build.gitCommit());
            RagPublicationProperties release = new RagPublicationProperties();
            release.setManifestPath(receipt.resolve("manifest.json").toString());
            release.setOperationPath(receipt.resolve("operation.json").toString());
            release.setArtifactPath(args[7]); release.setOperator(OPERATOR); release.setEnvironment(ENVIRONMENT);
            RagPublicationConfiguration configuration = new RagPublicationConfiguration();
            if ("PUBLISH".equals(args[4])) {
                // Explicit local operator confirmation for this synthetic child process only.
                PublicationIntegrationIT test = new PublicationIntegrationIT();
                test.drafts = syntheticDrafts();
                test.resources = Map.of(PublicationManifest.PLATFORM_HELP_RESOURCE, JSON.writeValueAsBytes(test.drafts),
                        PublicationManifest.CAPABILITIES_RESOURCE, "{\"syntheticFixture\":true}\n".getBytes(StandardCharsets.UTF_8));
                Map<String, String> hashes = new TreeMap<>(); test.resources.forEach((key, bytes) -> hashes.put(key, PublicationManifest.sha256(bytes)));
                PublicationManifest manifest = new PublicationManifest(1, build.gitCommit(), build.artifactSha256(), ENVIRONMENT, args[3], knowledge.embeddingSignature(), hashes,
                        catalog.epoch(), store.snapshotHash(), PublicationManifest.Operation.PUBLISH, null, false, true,
                        test.drafts.stream().filter(d -> PublicationManifest.userSourceIds().contains(d.sourceId()))
                                .map(d -> new PublicationManifest.Topic(d.sourceId(), d.version(), KnowledgeCatalog.hash(d.text()))).toList());
                OperationInput input = test.operationInput(manifest, 20);
                Files.createDirectory(receipt);
                Files.write(receipt.resolve("manifest.json"), input.bytes);
                Files.write(receipt.resolve("operation.json"), input.operationBytes);
                release.setEnabled(true);
                var prepared = configuration.preparedPublication(release, properties, new RagPublicationCommand.Invocation(true));
                configuration.publicationRunner(prepared, catalog.lifecycle(), knowledge).run(new DefaultApplicationArguments());
                Files.delete(receipt.resolve("operation.json"));
                System.out.println("PUBLICATION_IT_PRODUCTION_RUNNER mode=PUBLISH archivedBuildIdentity=true");
            } else if ("RESTORE".equals(args[4])) {
                assertFalse(Files.exists(receipt.resolve("operation.json")), "A normal restart must not require the operation record file.");
                release.setOperationPath("");
                release.setRecoveryEnabled(true);
                configuration.readOnlyRecoveryRunner(release, properties, catalog.lifecycle(), knowledge).run(new DefaultApplicationArguments());
                System.out.println("PUBLICATION_IT_PRODUCTION_RUNNER mode=READ_ONLY_RECOVERY archivedBuildIdentity=true");
            }
            SpringApplication app = new SpringApplication(HttpApplication.class);
            app.setDefaultProperties(Map.of("server.port", "0", "spring.main.banner-mode", "off"));
            app.addInitializers(context -> context.getBeanFactory().registerSingleton("syntheticReadController", new ReadController(knowledge, before)));
            var context = (ServletWebServerApplicationContext) app.run("--server.port=0", "--server.address=127.0.0.1",
                    "--spring.config.name=chen-release-isolated-test", "--spring.profiles.active=release-it");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { knowledge.close(); catalog.lifecycle().close(); }));
            Path marker = Path.of(args[5]);
            Path staged = marker.resolveSibling(marker.getFileName() + ".staged");
            Files.writeString(staged, Integer.toString(context.getWebServer().getPort()), StandardCharsets.UTF_8);
            Files.move(staged, marker, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
    }

    @RestController
    public static class ReadController {
        private final RagKnowledgeService knowledge;
        private final String before;
        ReadController(RagKnowledgeService knowledge, String before) { this.knowledge = knowledge; this.before = before; }
        @GetMapping("/it/status") public Map<String, Object> status() {
            Map<String, Object> result = new LinkedHashMap<>(knowledge.diagnostics()); result.put("before", before); return result;
        }
        @GetMapping("/it/read") public Map<String, Object> read(@RequestParam String title) {
            try { return Map.of("sourceIds", knowledge.retrieveByTitle(title).chunks().stream().map(d -> d.getMetadata().get("source_id")).distinct().toList()); }
            catch (RagFailure failure) { return Map.of("error", failure.code().name()); }
        }
    }
}
