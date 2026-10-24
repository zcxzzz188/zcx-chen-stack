package com.zcx.chenstack.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.zcx.chenstack.rag.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RagPublicationConfigurationTest {
    @TempDir Path directory;
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String GIT = "7".repeat(40);
    private static final String ARTIFACT = "a".repeat(64);

    private record Fixture(RagPublicationProperties release, RagProperties rag,
                           PublicationBuildIdentity.Identity identity, PublicationManifest manifest, byte[] approval) { }

    private Fixture fixture() throws Exception {
        KeyPair key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        List<String> ids = new ArrayList<>(PublicationManifest.userSourceIds());
        ids.add("help-history"); for (int i = 0; i < 12; i++) ids.add("help-admin-synthetic-" + i);
        var documents = ids.stream().map(id -> new KnowledgeDocument(id, "合成标题", "合成事实", "synthetic-1", "PLATFORM_HELP",
                "/", "NOT_PUBLISHED", "CONTENT_REVIEW_REQUIRED", Instant.EPOCH, List.of("合成"))).toList();
        Map<String, byte[]> resources = Map.of(PublicationManifest.PLATFORM_HELP_RESOURCE, JSON.writeValueAsBytes(documents),
                PublicationManifest.CAPABILITIES_RESOURCE, "{}".getBytes(StandardCharsets.UTF_8));
        Map<String, String> hashes = new LinkedHashMap<>();
        resources.forEach((name, bytes) -> hashes.put(name, PublicationManifest.sha256(bytes)));
        RagProperties rag = new RagProperties(); rag.setEnabled(true); rag.setCollection("chen_synthetic_release");
        rag.setEmbeddingApiKey("synthetic-not-a-real-key");
        String embedding = KnowledgeCatalog.hash(rag.getEmbeddingBaseUrl() + "|" + rag.getEmbeddingModel() + "|1024|chunk-v1");
        var manifest = new PublicationManifest(1, GIT, ARTIFACT, "isolated", rag.getCollection(), embedding, hashes, 4,
                "b".repeat(64), PublicationManifest.Operation.PUBLISH, null, false, false,
                PublicationManifest.userSourceIds().stream().map(id -> new PublicationManifest.Topic(id, "synthetic-1",
                        KnowledgeCatalog.hash("合成事实"))).toList());
        byte[] raw = JSON.writeValueAsBytes(manifest);
        var grant = new PublicationAuthorization.Grant("synthetic-approval", "reviewer-a", "executor-b", "isolated",
                PublicationManifest.sha256(raw), Instant.now().plusSeconds(3_600), 3, manifest.operation());
        byte[] payload = JSON.writeValueAsBytes(grant);
        Signature signer = Signature.getInstance("Ed25519"); signer.initSign(key.getPrivate()); signer.update(payload);
        byte[] approval = JSON.writeValueAsBytes(Map.of("payload", Base64.getUrlEncoder().withoutPadding().encodeToString(payload),
                "signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())));
        RagPublicationProperties release = new RagPublicationProperties(); release.setEnabled(true);
        release.setOperator("executor-b"); release.setEnvironment("isolated");
        release.setManifestPath(directory.resolve("manifest.json").toString());
        release.setApprovalPath(directory.resolve("approval.json").toString());
        release.setPublicKeyPath(directory.resolve("public.pem").toString());
        release.setArtifactPath(directory.resolve("running.jar").toString());
        Files.write(Path.of(release.getManifestPath()), raw); Files.write(Path.of(release.getApprovalPath()), approval);
        String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString(key.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        Files.writeString(Path.of(release.getPublicKeyPath()), pem, StandardCharsets.US_ASCII);
        return new Fixture(release, rag, new PublicationBuildIdentity.Identity(GIT, ARTIFACT, resources), manifest, approval);
    }

    @Test void defaultAndExplicitDisabledConfigurationsRegisterNoExecutionBeansOrInputReads() {
        try (var build = mockStatic(PublicationBuildIdentity.class)) {
            var runner = new ApplicationContextRunner().withUserConfiguration(RagPublicationConfiguration.class);
            for (boolean explicit : List.of(false, true)) {
                var context = explicit ? runner.withPropertyValues("chen.rag.release.enabled=false") : runner;
                context.run(application -> {
                    assertThat(application).hasNotFailed().hasSingleBean(RagPublicationProperties.class)
                            .doesNotHaveBean(RagPublicationConfiguration.Prepared.class).doesNotHaveBean(ApplicationRunner.class);
                    assertFalse(application.getBean(RagPublicationProperties.class).isEnabled());
                });
            }
            build.verifyNoInteractions();
        }
    }

    @Test void precheckReturnsOnlySignedValidatedDraftsWithoutDatabaseOrIndexDependencies() throws Exception {
        var fixture = fixture();
        try (var build = mockStatic(PublicationBuildIdentity.class)) {
            build.when(() -> PublicationBuildIdentity.read(any())).thenReturn(fixture.identity());
            var prepared = new RagPublicationConfiguration().preparedPublication(fixture.release(), fixture.rag());
            assertEquals(fixture.manifest(), prepared.manifest());
            assertEquals(PublicationManifest.sha256(fixture.approval()), prepared.approvalHash());
            assertEquals(39, prepared.drafts().size());
            assertTrue(prepared.drafts().stream().allMatch(d -> "NOT_PUBLISHED".equals(d.visibility())
                    && "CONTENT_REVIEW_REQUIRED".equals(d.reviewStatus())));
            assertThrows(UnsupportedOperationException.class, () -> prepared.drafts().clear());
            build.verify(() -> PublicationBuildIdentity.read(Path.of(fixture.release().getArtifactPath())), times(1));
        }
    }

    @Test void badApprovalFailsBeforeArtifactInspectionAndSanitizesRawInputErrors() throws Exception {
        var fixture = fixture(); Files.writeString(Path.of(fixture.release().getApprovalPath()), "PRIVATE_SENTINEL");
        try (var build = mockStatic(PublicationBuildIdentity.class)) {
            SecurityException failure = assertThrows(SecurityException.class,
                    () -> new RagPublicationConfiguration().preparedPublication(fixture.release(), fixture.rag()));
            assertEquals("PUBLICATION_PRECHECK_REJECTED", failure.getMessage()); assertNull(failure.getCause());
            build.verifyNoInteractions();
        }
    }

    @Test void precheckRejectsDifferentBuildIdentityAndDisabledRagBeforeExecutor() throws Exception {
        var fixture = fixture();
        try (var build = mockStatic(PublicationBuildIdentity.class)) {
            build.when(() -> PublicationBuildIdentity.read(any())).thenReturn(new PublicationBuildIdentity.Identity(
                    "8".repeat(40), ARTIFACT, fixture.identity().resources()));
            assertThrows(SecurityException.class, () -> new RagPublicationConfiguration().preparedPublication(fixture.release(), fixture.rag()));
            fixture.rag().setEnabled(false);
            assertThrows(SecurityException.class, () -> new RagPublicationConfiguration().preparedPublication(fixture.release(), fixture.rag()));
        }
    }

    @Test void boundsInputFilesAndRejectsDirectoriesMissingFilesAndPrivateKeyEnvelopes() throws Exception {
        Path file = directory.resolve("bounded"); Files.write(file, new byte[65]);
        assertThrows(SecurityException.class, () -> RagPublicationConfiguration.input(file.toString(), 64));
        assertThrows(SecurityException.class, () -> RagPublicationConfiguration.input(directory.toString(), 64));
        assertThrows(SecurityException.class, () -> RagPublicationConfiguration.input(directory.resolve("missing").toString(), 64));
        assertThrows(SecurityException.class, () -> RagPublicationConfiguration.input("", 64));
        assertThrows(SecurityException.class, () -> RagPublicationConfiguration.publicKey(
                "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----".getBytes(StandardCharsets.US_ASCII)));
        var rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair().getPublic();
        assertThrows(SecurityException.class, () -> RagPublicationConfiguration.publicKey(("-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(rsa.getEncoded()) + "\n-----END PUBLIC KEY-----").getBytes(StandardCharsets.US_ASCII)));
    }

    @Test void runnerDelegatesExactlyOnceWithPreparedInputs() throws Exception {
        var fixture = fixture();
        try (var build = mockStatic(PublicationBuildIdentity.class)) {
            build.when(() -> PublicationBuildIdentity.read(any())).thenReturn(fixture.identity());
            var config = new RagPublicationConfiguration();
            var prepared = config.preparedPublication(fixture.release(), fixture.rag());
            CatalogLifecycle lifecycle = mock(CatalogLifecycle.class); RagKnowledgeService knowledge = mock(RagKnowledgeService.class);
            try (var executors = mockConstruction(RagPublicationExecutor.class, (executor, context) -> {
                assertEquals(List.of(lifecycle, knowledge, prepared.manifest(), prepared.grant(), prepared.approvalHash(),
                        prepared.drafts()), context.arguments());
            })) {
                config.publicationRunner(prepared, lifecycle, knowledge).run(new DefaultApplicationArguments());
                assertEquals(1, executors.constructed().size()); verify(executors.constructed().getFirst(), times(1)).execute();
            }
        }
    }
}
