package com.zcx.chenstack.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zcx.chenstack.config.RagProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
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
    private KeyPair signingKey;

    @BeforeAll void startOnlyTaskOwnedInfrastructure() throws Exception {
        assertEquals("true", System.getProperty("chen.release.it"),
                "Explicit integration opt-in required; this suite must not silently skip.");
        signingKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        drafts = syntheticDrafts();
        resources = Map.of(PublicationManifest.PLATFORM_HELP_RESOURCE, JSON.writeValueAsBytes(drafts),
                PublicationManifest.CAPABILITIES_RESOURCE, "{\"syntheticFixture\":true}\n".getBytes(StandardCharsets.UTF_8));
        docker = new DockerFixture(temporary);
        docker.start();
    }

    @AfterAll void stopOnlyTaskOwnedInfrastructure() throws Exception {
        if (docker != null) docker.close();
    }

    @Test void validPublicationPersistsExactly39ApprovedSources() throws Exception {
        try (Fixture f = fixture()) {
            Approval approval = approval(f, PublicationManifest.Operation.PUBLISH, 20, null);
            execute(f, approval);
            assertReady(f);
            assertEquals(1, f.catalog().epoch());
            assertEquals(PublicationManifest.userSourceIds(), f.catalog().chunks().stream()
                    .map(d -> (String) d.getMetadata().get("source_id")).collect(java.util.stream.Collectors.toSet()));
            assertEquals(39, f.catalog().chunks().size());
            assertEquals(40, points(f).size()); // 39 facts plus the embedding-signature marker.
            assertEquals(13, f.catalog().documents().stream().filter(d -> "NOT_PUBLISHED".equals(d.visibility())).count());
            assertEquals(39, scalar(f.source, "SELECT COUNT(*) FROM rag_source_state WHERE JSON_UNQUOTE(JSON_EXTRACT(document_json,'$.reviewStatus'))='DOCUMENT_REVIEWED'"));
            assertEquals("COMPLETED", f.store.operation(approval.grant.approvalId()).status());
            for (KnowledgeDocument draft : drafts) {
                KnowledgeEvidence evidence = f.knowledge.retrieveByTitle(draft.title());
                assertEquals(PublicationManifest.userSourceIds().contains(draft.sourceId()), !evidence.chunks().isEmpty(), draft.sourceId());
            }
        }
    }

    @Test void signedBootstrapInitializesOnlyMigratedEmptyTablesAndIsResumable() throws Exception {
        EmptyTarget target = emptyTarget();
        RagProperties properties = properties(target.collection, docker.grpcPort);
        String signature = KnowledgeCatalog.hash(properties.getEmbeddingBaseUrl() + "|" + properties.getEmbeddingModel() + "|1024|chunk-v1");
        Map<String, String> hashes = new TreeMap<>(); resources.forEach((path, bytes) -> hashes.put(path, PublicationManifest.sha256(bytes)));
        PublicationManifest manifest = new PublicationManifest(1, BUILD, ARTIFACT, ENVIRONMENT, target.collection, signature,
                hashes, 0, "EMPTY", PublicationManifest.Operation.PUBLISH, null, true, true,
                drafts.stream().filter(d -> PublicationManifest.userSourceIds().contains(d.sourceId()))
                        .map(d -> new PublicationManifest.Topic(d.sourceId(), d.version(), KnowledgeCatalog.hash(d.text()))).toList());
        Approval signed = sign(manifest, 20, Instant.now().plusSeconds(600));
        List<KnowledgeDocument> validated = manifest.validate(new PublicationManifest.RuntimeIdentity(BUILD, ARTIFACT,
                ENVIRONMENT, target.collection, signature, 0, "EMPTY"), resources);
        Approval approval = new Approval(manifest, signed.bytes, signed.envelope, signed.grant, validated);
        KnowledgeCatalog opened = RagPublicationExecutor.openOrInitialize(target.source, drafts, manifest, signed.grant,
                PublicationManifest.sha256(signed.envelope));
        try (Fixture f = new Fixture(target.source, target.schema, target.collection, opened)) {
            assertEquals(0, opened.epoch());
            assertEquals(52, opened.sourceIds().size());
            assertTrue(opened.chunks().isEmpty());
            assertEquals(1, scalar(target.source, "SELECT COUNT(*) FROM rag_release_event WHERE event_type='BOOTSTRAP'"));
            execute(f, approval);
            assertReady(f);
        }
        KnowledgeCatalog resumed = RagPublicationExecutor.openOrInitialize(target.source, drafts, manifest, signed.grant,
                PublicationManifest.sha256(signed.envelope));
        try (Fixture f = new Fixture(target.source, target.schema, target.collection, resumed)) {
            assertRecoveryRequired(f);
            execute(f, approval);
            assertReady(f);
            assertEquals(1, resumed.epoch());
            assertEquals(1, scalar(target.source, "SELECT COUNT(*) FROM rag_release_event WHERE event_type='BOOTSTRAP'"));
        }
    }

    @Test void simultaneousExecutorCannotTakeSameProcessPublicationLease() throws Exception {
        try (Fixture f = fixture(); var workers = Executors.newSingleThreadExecutor()) {
            Approval approval = approval(f, PublicationManifest.Operation.PUBLISH, 20, null);
            f.model.entered = new CountDownLatch(1); f.model.proceed = new CountDownLatch(1);
            var first = workers.submit(() -> execute(f, approval));
            try {
                assertTrue(f.model.entered.await(10, TimeUnit.SECONDS));
                IllegalStateException collision = assertThrows(IllegalStateException.class, () -> execute(f, approval));
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
            executeFailure(f, approval(f, PublicationManifest.Operation.PUBLISH, 20, null));
            assertEquals(0, f.catalog().epoch());
            assertEquals(0, f.model.calls.get());
            assertRecoveryRequired(f);
            assertEquals(1, points(f).size());
            assertEquals("foreign-owner", points(f).getFirst().path("payload").path("release_owner").asText());
        }
    }

    @Test void differentCatalogDatabaseCannotAdoptSameEnvironmentCollection() throws Exception {
        try (Fixture first = fixture()) {
            execute(first, approval(first, PublicationManifest.Operation.PUBLISH, 20, null));
            assertReady(first);
            List<JsonNode> before = points(first);
            EmptyTarget secondTarget = emptyTarget();
            JdbcCatalogStore.initialize(secondTarget.source, drafts);
            try (Fixture second = new Fixture(secondTarget.source, secondTarget.schema, first.collection)) {
                assertNotEquals(first.schema, second.schema);
                assertNotEquals(first.store.databaseIdentityHash(), second.store.databaseIdentityHash());
                executeFailure(second, approval(second, PublicationManifest.Operation.PUBLISH, 20, null));
                assertEquals(0, second.catalog().epoch());
                assertEquals(0, second.model.calls.get());
                assertEquals(0, scalar(second.source, "SELECT COUNT(*) FROM rag_source_state WHERE JSON_UNQUOTE(JSON_EXTRACT(document_json,'$.visibility'))='PUBLIC'"));
                assertRecoveryRequired(second);
                assertEquals(before, points(first), "Another database must not replace any existing target payload or owner marker.");
                assertReady(first);
            }
        }
    }

    @Test void invalidSignatureCannotMutateCatalogOrIndex() throws Exception {
        try (Fixture f = fixture()) {
            Approval valid = approval(f, PublicationManifest.Operation.PUBLISH, 20, null);
            Map<String, String> envelope = JSON.readValue(valid.envelope, Map.class);
            byte[] signature = Base64.getUrlDecoder().decode(envelope.get("signature"));
            signature[0] ^= 1;
            envelope.put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signature));
            assertThrows(SecurityException.class, () -> verify(JSON.writeValueAsBytes(envelope), valid.bytes, valid.manifest.operation()));
            assertUntouched(f);
        }
    }

    @Test void changedResourceBytesCannotUseOriginalApproval() throws Exception {
        try (Fixture f = fixture()) {
            Approval valid = approval(f, PublicationManifest.Operation.PUBLISH, 20, null);
            Map<String, byte[]> changed = new HashMap<>(resources);
            changed.put(PublicationManifest.PLATFORM_HELP_RESOURCE, (new String(resources.get(PublicationManifest.PLATFORM_HELP_RESOURCE), StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8));
            assertThrows(SecurityException.class, () -> valid.manifest.validate(identity(f), changed));
            assertUntouched(f);
        }
    }

    @Test void wrongRuntimeIdentityCannotPublish() throws Exception {
        try (Fixture f = fixture()) {
            Approval valid = approval(f, PublicationManifest.Operation.PUBLISH, 20, null);
            var original = identity(f);
            for (var wrong : List.of(
                    new PublicationManifest.RuntimeIdentity(BUILD, ARTIFACT, "other", f.collection, f.knowledge.embeddingSignature(), 0, f.store.snapshotHash()),
                    new PublicationManifest.RuntimeIdentity(BUILD, ARTIFACT, ENVIRONMENT, "chen_wrong", f.knowledge.embeddingSignature(), 0, f.store.snapshotHash()),
                    new PublicationManifest.RuntimeIdentity(BUILD, "c".repeat(64), ENVIRONMENT, f.collection, f.knowledge.embeddingSignature(), 0, f.store.snapshotHash()))) {
                assertThrows(SecurityException.class, () -> valid.manifest.validate(wrong, resources));
            }
            assertThrows(SecurityException.class, () -> PublicationAuthorization.verify(valid.envelope, valid.bytes,
                    signingKey.getPublic(), "other-operator", ENVIRONMENT, valid.manifest.operation(), Instant.now()));
            assertEquals(0, original.generation());
            assertUntouched(f);
        }
    }

    @Test void staleCatalogPreconditionCannotPublish() throws Exception {
        try (Fixture f = fixture()) {
            Approval old = approval(f, PublicationManifest.Operation.PUBLISH, 20, null);
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
            Approval valid = approval(f, PublicationManifest.Operation.PUBLISH, 20, null);
            for (String excluded : List.of("help-history", "help-admin-articles")) {
                List<PublicationManifest.Topic> changed = new ArrayList<>(valid.manifest.topics());
                changed.set(0, new PublicationManifest.Topic(excluded, "synthetic-1", "e".repeat(64)));
                PublicationManifest forbidden = withTopics(valid.manifest, changed);
                Approval signedForbidden = sign(forbidden, 20, Instant.now().plusSeconds(600));
                assertNotNull(signedForbidden.grant); // A valid signature does not relax content scope.
                assertThrows(SecurityException.class, () -> forbidden.validate(identity(f), resources));
            }
            assertUntouched(f);
        }
    }

    @Test void completedReplayDoesNotEmbedOrAdvanceGeneration() throws Exception {
        try (Fixture f = fixture()) {
            Approval approval = approval(f, PublicationManifest.Operation.PUBLISH, 20, null);
            execute(f, approval);
            int calls = f.model.calls.get();
            long events = scalar(f.source, "SELECT COUNT(*) FROM rag_release_event");
            execute(f, approval);
            assertReady(f);
            assertEquals(1, f.catalog().epoch());
            assertEquals(calls, f.model.calls.get());
            assertEquals(events, scalar(f.source, "SELECT COUNT(*) FROM rag_release_event"));
        }
    }

    @Test void restartRequiresAndCanRecoverSameApprovedReceipt() throws Exception {
        Fixture f = fixture();
        Approval approval = approval(f, PublicationManifest.Operation.PUBLISH, 20, null);
        execute(f, approval);
        String oldAuthority = f.lifecycle.authorityId();
        long spent = f.store.operation(approval.grant.approvalId()).embeddingRequests();
        Fixture restarted = reopen(f);
        try (restarted) {
            assertNotEquals(oldAuthority, restarted.lifecycle.authorityId());
            assertRecoveryRequired(restarted);
            execute(restarted, approval);
            assertReady(restarted);
            assertEquals(1, restarted.catalog().epoch());
            assertTrue(restarted.store.operation(approval.grant.approvalId()).embeddingRequests() > spent);
            assertEquals(restarted.lifecycle.authorityId(), restarted.store.operation(approval.grant.approvalId()).indexAuthority());
        }
    }

    @Test void partialIndexFailureLeavesRealPointsButNoReadAuthority() throws Exception {
        try (Fixture f = fixture()) {
            f.model.failAt = 2;
            executeFailure(f, approval(f, PublicationManifest.Operation.PUBLISH, 20, null));
            assertEquals(11, points(f).size(), "First ten actual Qdrant facts must have committed before injected second-batch failure.");
            assertEquals(1, f.catalog().epoch());
            assertRecoveryRequired(f);
        }
    }

    @Test void partialIndexResumeUsesDurableBudgetAndSingleCatalogApply() throws Exception {
        Fixture f = fixture();
        Approval approval = approval(f, PublicationManifest.Operation.PUBLISH, 20, null);
        f.model.failAt = 2;
        executeFailure(f, approval);
        assertEquals(2, f.store.operation(approval.grant.approvalId()).embeddingRequests());
        Fixture restarted = reopen(f);
        try (restarted) {
            execute(restarted, approval);
            assertReady(restarted);
            assertEquals(1, restarted.catalog().epoch());
            assertEquals(6, restarted.store.operation(approval.grant.approvalId()).embeddingRequests());
            assertEquals(1, scalar(restarted.source, "SELECT COUNT(*) FROM rag_release_event WHERE event_type='CATALOG_APPLIED'"));
            assertEquals(40, points(restarted).size());
        }
    }

    @Test void sqlCommitFailureBeforeCommitRollsBackAndCloses() throws Exception {
        Fixture f = fixture();
        Approval approval = approval(f, PublicationManifest.Operation.PUBLISH, 20, null);
        f.fault.arm(false);
        executeFailure(f, approval);
        assertEquals(0, scalar(f.source, "SELECT generation FROM rag_catalog_state WHERE id=1"));
        assertEquals(0, scalar(f.source, "SELECT MAX(generation) FROM rag_release_event WHERE event_type='FAILED'"));
        assertThrows(CatalogLifecycle.Invalidated.class, f.lifecycle::snapshot);
        Fixture restarted = reopen(f);
        try (restarted) {
            execute(restarted, approval);
            assertReady(restarted);
            assertEquals(1, restarted.catalog().epoch());
        }
    }

    @Test void sqlCommitThrowsAfterCommitRecoversDurableOutcome() throws Exception {
        Fixture f = fixture();
        Approval approval = approval(f, PublicationManifest.Operation.PUBLISH, 20, null);
        f.fault.arm(true);
        executeFailure(f, approval);
        assertEquals(1, scalar(f.source, "SELECT generation FROM rag_catalog_state WHERE id=1"));
        assertEquals(1, scalar(f.source, "SELECT MAX(generation) FROM rag_release_event WHERE event_type='FAILED'"));
        assertThrows(CatalogLifecycle.Invalidated.class, f.lifecycle::snapshot);
        Fixture restarted = reopen(f);
        try (restarted) {
            execute(restarted, approval);
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

    @Test void cumulativeApprovalBudgetCannotResetOnRestart() throws Exception {
        Fixture f = fixture();
        Approval approval = approval(f, PublicationManifest.Operation.PUBLISH, 2, null);
        executeFailure(f, approval);
        assertEquals(2, f.model.calls.get());
        Fixture restarted = reopen(f);
        try (restarted) {
            executeFailure(restarted, approval);
            assertEquals(0, restarted.model.calls.get());
            assertEquals(2, restarted.store.operation(approval.grant.approvalId()).embeddingRequests());
            assertRecoveryRequired(restarted);
        }
    }

    @Test void revocationDeleteFailureStillPersistsAndInvalidatesPreviouslyReleasedEvidence() throws Exception {
        try (Fixture f = fixture()) {
            execute(f, approval(f, PublicationManifest.Operation.PUBLISH, 20, null));
            KnowledgeEvidence released = f.knowledge.retrieveByTitle("Synthetic help-login");
            assertFalse(released.chunks().isEmpty());
            Approval revoke = approval(f, PublicationManifest.Operation.REVOKE, 0, "help-login");
            DockerFixture.command("pause", docker.qdrant);
            try { executeFailure(f, revoke); }
            finally { DockerFixture.command("unpause", docker.qdrant); }
            assertEquals("REVOKED", f.catalog().source("help-login").reviewStatus());
            assertThrows(CatalogLifecycle.Invalidated.class, released::context);
            assertRecoveryRequired(f);
            assertTrue(points(f).stream().anyMatch(p -> "help-login".equals(p.path("payload").path("source_id").asText())),
                    "The actual stale physical copy must still exist while logical revocation remains effective.");
            assertEquals("FAILED", f.store.operation(revoke.grant.approvalId()).status());
        }
    }

    @Test void recoveryDoesNotReviveRevokedOrExcludedSources() throws Exception {
        try (Fixture f = fixture()) {
            execute(f, approval(f, PublicationManifest.Operation.PUBLISH, 20, null));
            execute(f, approval(f, PublicationManifest.Operation.REVOKE, 0, "help-login"));
            execute(f, approval(f, PublicationManifest.Operation.RECOVER, 20, null));
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
            Approval old = approval(f, PublicationManifest.Operation.PUBLISH, 20, null);
            execute(f, old);
            execute(f, approval(f, PublicationManifest.Operation.REVOKE, 0, "help-login"));
            executeFailure(f, old);
            assertEquals("REVOKED", f.catalog().source("help-login").reviewStatus());
            assertEquals(2, f.catalog().epoch());
            assertRecoveryRequired(f);
        }
    }

    @Test void tamperedPhysicalMetadataCannotBecomeEvidence() throws Exception {
        try (Fixture f = fixture()) {
            execute(f, approval(f, PublicationManifest.Operation.PUBLISH, 20, null));
            Document chunk = f.catalog().chunks().getFirst();
            qdrant("POST", "/collections/" + f.collection + "/points/payload?wait=true",
                    Map.of("payload", Map.of("content_hash", "forged"), "points", List.of(chunk.getId())));
            String source = (String) chunk.getMetadata().get("source_id");
            RagFailure rejected = assertThrows(RagFailure.class, () -> f.knowledge.retrieveByTitle(f.catalog().source(source).title()));
            assertEquals(RagFailure.Code.INDEX_MISMATCH, rejected.code());
        }
    }

    @Test void expiredApprovalCannotStartExternalWork() throws Exception {
        try (Fixture f = fixture()) {
            PublicationManifest manifest = manifest(f, PublicationManifest.Operation.PUBLISH, null);
            assertThrows(SecurityException.class, () -> sign(manifest, 20, Instant.EPOCH));
            Approval valid = sign(manifest, 20, Instant.now().plusSeconds(600));
            assertThrows(SecurityException.class, () -> verify(valid.envelope, valid.bytes, PublicationManifest.Operation.RECOVER));
            byte[] altered = (new String(valid.bytes, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);
            assertThrows(SecurityException.class, () -> verify(valid.envelope, altered, manifest.operation()));
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
        try (HttpProcess restarted = startHttpProcess(schema, collection, "NONE")) {
            assertEquals("RECOVERY_REQUIRED", get(restarted.port, "/it/status").path("ragAvailability").asText());
            assertEquals("RECOVERY_REQUIRED", get(restarted.port, "/it/read?title=Synthetic%20help-login").path("error").asText());
        }
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

    private record Approval(PublicationManifest manifest, byte[] bytes, byte[] envelope,
                            PublicationAuthorization.Grant grant, List<KnowledgeDocument> validatedDrafts) { }

    private Approval approval(Fixture f, PublicationManifest.Operation operation, int budget, String revoke) throws Exception {
        PublicationManifest manifest = manifest(f, operation, revoke);
        Approval signed = sign(manifest, budget, Instant.now().plusSeconds(600));
        return new Approval(manifest, signed.bytes, signed.envelope, signed.grant, manifest.validate(identity(f), resources));
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

    private Approval sign(PublicationManifest manifest, int budget, Instant expiry) throws Exception {
        byte[] bytes = JSON.writeValueAsBytes(manifest);
        var grant = new PublicationAuthorization.Grant("it_" + UUID.randomUUID().toString().replace("-", ""),
                "synthetic-approver", OPERATOR, ENVIRONMENT, PublicationManifest.sha256(bytes), expiry, budget, manifest.operation());
        byte[] payload = JSON.writeValueAsBytes(grant);
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(signingKey.getPrivate()); signature.update(payload);
        byte[] envelope = JSON.writeValueAsBytes(Map.of("payload", Base64.getUrlEncoder().withoutPadding().encodeToString(payload),
                "signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign())));
        return new Approval(manifest, bytes, envelope, verify(envelope, bytes, manifest.operation()), List.of());
    }

    private PublicationAuthorization.Grant verify(byte[] envelope, byte[] manifest, PublicationManifest.Operation operation) {
        return PublicationAuthorization.verify(envelope, manifest, signingKey.getPublic(), OPERATOR, ENVIRONMENT, operation, Instant.now());
    }

    private Map<String, Object> execute(Fixture f, Approval approval) {
        return new RagPublicationExecutor(f.lifecycle, f.knowledge, approval.manifest, approval.grant,
                PublicationManifest.sha256(approval.envelope), approval.validatedDrafts).execute();
    }

    private void executeFailure(Fixture f, Approval approval) {
        try { execute(f, approval); }
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
        final String password = UUID.randomUUID().toString();
        final Path temporary;
        int mysqlPort, grpcPort, restPort;
        DockerFixture(Path temporary) { this.temporary = temporary; }
        void start() throws Exception {
            // Explicit no-pull prevents accidental network access or dependency drift.
            command("image", "inspect", "mysql:8.0"); command("image", "inspect", "qdrant/qdrant:v1.13.6");
            try {
                command("network", "create", "--driver", "bridge", "--label", "chen.release.it=" + id, network);
                Path env = temporary.resolve("mysql.env");
                Files.writeString(env, "MYSQL_ROOT_PASSWORD=" + password + "\nMYSQL_ROOT_HOST=%\n", StandardCharsets.UTF_8);
                command("run", "--pull=never", "--detach", "--name", mysql, "--label", "chen.release.it=" + id,
                        "--network", network, "--dns", "127.0.0.1", "--publish", "127.0.0.1::3306", "--env-file", env.toString(), "mysql:8.0");
                command("run", "--pull=never", "--detach", "--name", qdrant, "--label", "chen.release.it=" + id,
                        "--network", network, "--dns", "127.0.0.1", "--env", "QDRANT__TELEMETRY_DISABLED=true",
                        "--publish", "127.0.0.1::6333", "--publish", "127.0.0.1::6334", "qdrant/qdrant:v1.13.6");
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
            assertTrue(command("ps", "--all", "--filter", "label=chen.release.it=" + id, "--format", "{{.Names}}").isBlank());
            assertTrue(command("network", "ls", "--filter", "label=chen.release.it=" + id, "--format", "{{.Name}}").isBlank());
            System.out.println("PUBLICATION_IT_CLEANED label=" + id);
        }
    }

    private HttpProcess startHttpProcess(String schema, String collection, String action) throws Exception {
        Path marker = temporary.resolve("http-" + UUID.randomUUID() + ".port");
        Path log = temporary.resolve("http-" + UUID.randomUUID() + ".log");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp", classpath,
                HttpApplication.class.getName(), Integer.toString(docker.mysqlPort), Integer.toString(docker.grpcPort), schema,
                collection, action, marker.toString());
        builder.environment().put("CHEN_SYNTHETIC_MYSQL_PASSWORD", docker.password);
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = builder.start();
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (process.isAlive() && !Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(100);
        if (!Files.exists(marker)) {
            process.destroyForcibly();
            String details = Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "NO_LOG";
            fail("Synthetic Spring Boot process did not start: " + details.substring(Math.max(0, details.length() - 8000)));
        }
        return new HttpProcess(process, Integer.parseInt(Files.readString(marker).strip()));
    }

    private record HttpProcess(Process process, int port) implements AutoCloseable {
        @Override public void close() throws Exception {
            process.destroy(); if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }

    /** A separate real servlet JVM; only the HTTP observation surface is test-specific. */
    @Configuration(proxyBeanMethods = false)
    @Import({ServletWebServerFactoryAutoConfiguration.class, DispatcherServletAutoConfiguration.class,
            WebMvcAutoConfiguration.class, JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
    public static class HttpApplication {
        public static void main(String[] args) throws Exception {
            int mysqlPort = Integer.parseInt(args[0]), grpcPort = Integer.parseInt(args[1]);
            DriverManagerDataSource source = new DriverManagerDataSource("jdbc:mysql://127.0.0.1:" + mysqlPort + "/" + args[2]
                    + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=UTC", "root", System.getenv("CHEN_SYNTHETIC_MYSQL_PASSWORD"));
            JdbcCatalogStore store = JdbcCatalogStore.open(source);
            KnowledgeCatalog catalog = KnowledgeCatalog.restore(store);
            RagProperties properties = properties(args[3], grpcPort);
            GuardedEmbeddingModel embedding = new GuardedEmbeddingModel(new LocalEmbedding(), properties);
            RagKnowledgeService knowledge = new RagKnowledgeService(properties, catalog, new QdrantKnowledgeIndex(properties, embedding), embedding);
            String before = (String) knowledge.diagnostics().get("ragAvailability");
            if ("PUBLISH".equals(args[4])) {
                // Independent ephemeral approval for this synthetic child process only.
                PublicationIntegrationIT test = new PublicationIntegrationIT();
                test.signingKey = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                test.drafts = syntheticDrafts();
                test.resources = Map.of(PublicationManifest.PLATFORM_HELP_RESOURCE, JSON.writeValueAsBytes(test.drafts),
                        PublicationManifest.CAPABILITIES_RESOURCE, "{\"syntheticFixture\":true}\n".getBytes(StandardCharsets.UTF_8));
                Map<String, String> hashes = new TreeMap<>(); test.resources.forEach((key, bytes) -> hashes.put(key, PublicationManifest.sha256(bytes)));
                PublicationManifest manifest = new PublicationManifest(1, BUILD, ARTIFACT, ENVIRONMENT, args[3], knowledge.embeddingSignature(), hashes,
                        catalog.epoch(), store.snapshotHash(), PublicationManifest.Operation.PUBLISH, null, false, true,
                        test.drafts.stream().filter(d -> PublicationManifest.userSourceIds().contains(d.sourceId()))
                                .map(d -> new PublicationManifest.Topic(d.sourceId(), d.version(), KnowledgeCatalog.hash(d.text()))).toList());
                Approval approval = test.sign(manifest, 20, Instant.now().plusSeconds(600));
                List<KnowledgeDocument> approved = manifest.validate(new PublicationManifest.RuntimeIdentity(BUILD, ARTIFACT, ENVIRONMENT,
                        args[3], knowledge.embeddingSignature(), catalog.epoch(), store.snapshotHash()), test.resources);
                new RagPublicationExecutor(catalog.lifecycle(), knowledge, manifest, approval.grant,
                        PublicationManifest.sha256(approval.envelope), approved).execute();
            }
            SpringApplication app = new SpringApplication(HttpApplication.class);
            app.setDefaultProperties(Map.of("server.port", "0", "spring.main.banner-mode", "off"));
            app.addInitializers(context -> context.getBeanFactory().registerSingleton("syntheticReadController", new ReadController(knowledge, before)));
            var context = (ServletWebServerApplicationContext) app.run("--server.port=0", "--server.address=127.0.0.1",
                    "--spring.config.name=chen-release-isolated-test", "--spring.profiles.active=release-it");
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { knowledge.close(); catalog.lifecycle().close(); }));
            Files.writeString(Path.of(args[5]), Integer.toString(context.getWebServer().getPort()), StandardCharsets.UTF_8);
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
