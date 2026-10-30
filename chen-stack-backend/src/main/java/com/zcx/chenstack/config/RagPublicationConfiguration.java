package com.zcx.chenstack.config;

import com.zcx.chenstack.rag.*;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagPublicationProperties.class)
public class RagPublicationConfiguration {
    @Bean
    public ReleaseProviderBudget releaseProviderBudget(RagPublicationProperties release, ObjectProvider<NormalAiUsage> normal) {
        NormalAiUsage usage = normal.getIfAvailable();
        // 本地发布命令不扫描正式 Web 计量组件，仍使用独立限次授权。
        if (usage != null && usage.enabled()) return ReleaseProviderBudget.continuous(usage, NormalAiUsage.Purpose.CHAT);
        return new ReleaseProviderBudget(release.getBudgetLedgerPath(), release.getBudgetId(),
                release.getMaxEmbeddingHttpAttempts(), release.getMaxChatHttpAttempts());
    }

    public record Prepared(PublicationManifest manifest, PublicationAuthorization.Grant grant,
                           String operationHash, List<KnowledgeDocument> drafts) {
        public Prepared { drafts = List.copyOf(drafts); }
    }

    @Bean
    @ConditionalOnProperty(prefix = "chen.rag.release", name = "enabled", havingValue = "true")
    public Prepared preparedPublication(RagPublicationProperties release, RagProperties rag,
                                        RagPublicationCommand.Invocation invocation) {
        try {
            rag.validate();
            if (invocation == null || !invocation.localOnly() || !release.isEnabled() || release.isRecoveryEnabled()
                    || !rag.isEnabled() || rag.getEmbeddingApiKey() == null || rag.getEmbeddingApiKey().isBlank()) {
                throw new SecurityException("PUBLICATION_REQUIRES_LOCAL_OPERATOR_COMMAND");
            }
            byte[] manifestBytes = input(release.getManifestPath(), 65_536);
            byte[] operationBytes = input(release.getOperationPath(), 8_192);
            PublicationManifest manifest = PublicationManifest.parse(manifestBytes);
            var grant = PublicationAuthorization.verify(operationBytes, manifestBytes, release.getOperator(),
                    release.getEnvironment(), manifest.operation());
            if (release.getArtifactPath() == null || release.getArtifactPath().isBlank()) {
                throw new SecurityException("PUBLICATION_ARTIFACT_REQUIRED");
            }
            var identity = PublicationBuildIdentity.read(Path.of(release.getArtifactPath()));
            String embedding = KnowledgeCatalog.hash(rag.getEmbeddingBaseUrl() + "|" + rag.getEmbeddingModel() + "|1024|chunk-v1");
            // 资源来自实际运行包；真实数据库前提由执行器重新核对。
            var runtime = new PublicationManifest.RuntimeIdentity(identity.gitCommit(), identity.artifactSha256(),
                    release.getEnvironment(), rag.getCollection(), embedding,
                    manifest.expectedGeneration(), manifest.expectedSnapshotSha256());
            var drafts = manifest.validateResources(runtime, identity.resources());
            return new Prepared(manifest, grant, PublicationManifest.sha256(operationBytes), drafts);
        } catch (Exception exception) {
            throw new SecurityException("PUBLICATION_PRECHECK_REJECTED");
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = "chen.rag.release", name = "enabled", havingValue = "true")
    public ApplicationRunner publicationRunner(Prepared prepared, CatalogLifecycle lifecycle, RagKnowledgeService knowledge) {
        return arguments -> new RagPublicationExecutor(lifecycle, knowledge, prepared.manifest(), prepared.grant(),
                prepared.operationHash(), prepared.drafts()).execute();
    }

    @Bean
    @ConditionalOnProperty(prefix = "chen.rag.release", name = "recovery-enabled", havingValue = "true")
    public ApplicationRunner readOnlyRecoveryRunner(RagPublicationProperties release, RagProperties rag,
            CatalogLifecycle lifecycle, RagKnowledgeService knowledge) {
        if (release.isEnabled() || !rag.isEnabled()) throw new SecurityException("RECOVERY_REQUIRES_EXCLUSIVE_READ_ONLY_MODE");
        rag.validate();
        return arguments -> {
            try {
                byte[] manifestBytes = input(release.getManifestPath(), 65_536);
                var identity = PublicationBuildIdentity.read(Path.of(release.getArtifactPath()));
                var runtime = new PublicationManifest.RuntimeIdentity(identity.gitCommit(), identity.artifactSha256(),
                        release.getEnvironment(), rag.getCollection(),
                        KnowledgeCatalog.hash(rag.getEmbeddingBaseUrl() + "|" + rag.getEmbeddingModel() + "|1024|chunk-v1"),
                        lifecycle.snapshot().epoch(), "UNUSED_READ_ONLY_RECOVERY");
                RagPublicationRecovery.restore(lifecycle, knowledge, manifestBytes, release.getOperator(),
                        release.getEnvironment(), runtime, identity.resources());
            } catch (Exception error) {
                throw new SecurityException("PUBLICATION_READ_ONLY_RECOVERY_REJECTED");
            }
        };
    }

    static byte[] input(String value, int limit) {
        try {
            if (value == null || value.isBlank()) throw new SecurityException("PUBLICATION_INPUT_REQUIRED");
            Path path = Path.of(value);
            if (!Files.isRegularFile(path) || Files.size(path) < 1 || Files.size(path) > limit) {
                throw new SecurityException("PUBLICATION_INPUT_INVALID");
            }
            try (var stream = Files.newInputStream(path)) {
                byte[] bytes = stream.readNBytes(limit + 1);
                if (bytes.length < 1 || bytes.length > limit) throw new SecurityException("PUBLICATION_INPUT_INVALID");
                return bytes;
            }
        } catch (Exception exception) {
            throw new SecurityException("PUBLICATION_INPUT_INVALID");
        }
    }
}
