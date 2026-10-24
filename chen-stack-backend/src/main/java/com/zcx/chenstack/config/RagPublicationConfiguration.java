package com.zcx.chenstack.config;

import com.zcx.chenstack.rag.CatalogLifecycle;
import com.zcx.chenstack.rag.KnowledgeCatalog;
import com.zcx.chenstack.rag.KnowledgeDocument;
import com.zcx.chenstack.rag.PublicationAuthorization;
import com.zcx.chenstack.rag.PublicationBuildIdentity;
import com.zcx.chenstack.rag.PublicationManifest;
import com.zcx.chenstack.rag.RagKnowledgeService;
import com.zcx.chenstack.rag.RagPublicationExecutor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RagPublicationProperties.class)
public class RagPublicationConfiguration {
    private static final Pattern PUBLIC_KEY = Pattern.compile(
            "\\A-----BEGIN PUBLIC KEY-----\\s+([A-Za-z0-9+/=\\s]+)-----END PUBLIC KEY-----\\s*\\z");

    public record Prepared(PublicationManifest manifest, PublicationAuthorization.Grant grant,
                           String approvalHash, List<KnowledgeDocument> drafts) {
        public Prepared { drafts = List.copyOf(drafts); }
    }

    @Bean
    @ConditionalOnProperty(prefix = "chen.rag.release", name = "enabled", havingValue = "true")
    public Prepared preparedPublication(RagPublicationProperties release, RagProperties rag) {
        try {
            rag.validate();
            if (!release.isEnabled() || !rag.isEnabled() || rag.getEmbeddingApiKey() == null || rag.getEmbeddingApiKey().isBlank()) {
                throw new SecurityException("PUBLICATION_REQUIRES_DURABLE_RAG");
            }
            byte[] manifestBytes = input(release.getManifestPath(), 65_536);
            byte[] approvalBytes = input(release.getApprovalPath(), 16_384);
            PublicKey key = publicKey(input(release.getPublicKeyPath(), 8_192));
            PublicationManifest manifest = PublicationManifest.parse(manifestBytes);
            var grant = PublicationAuthorization.verify(approvalBytes, manifestBytes, key, release.getOperator(),
                    release.getEnvironment(), manifest.operation(), Instant.now());
            if (release.getArtifactPath() == null || release.getArtifactPath().isBlank()) {
                throw new SecurityException("PUBLICATION_ARTIFACT_REQUIRED");
            }
            var identity = PublicationBuildIdentity.read(Path.of(release.getArtifactPath()));
            String embedding = KnowledgeCatalog.hash(rag.getEmbeddingBaseUrl() + "|" + rag.getEmbeddingModel() + "|1024|chunk-v1");
            // 此处只校验构建与资源；真实数据库代际必须由执行器再读取，不能信任清单值。
            var runtime = new PublicationManifest.RuntimeIdentity(identity.gitCommit(), identity.artifactSha256(),
                    release.getEnvironment(), rag.getCollection(), embedding,
                    manifest.expectedGeneration(), manifest.expectedSnapshotSha256());
            var drafts = manifest.validateResources(runtime, identity.resources());
            return new Prepared(manifest, grant, PublicationManifest.sha256(approvalBytes), drafts);
        } catch (Exception exception) {
            throw new SecurityException("PUBLICATION_PRECHECK_REJECTED");
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = "chen.rag.release", name = "enabled", havingValue = "true")
    public ApplicationRunner publicationRunner(Prepared prepared, CatalogLifecycle lifecycle, RagKnowledgeService knowledge) {
        return arguments -> new RagPublicationExecutor(lifecycle, knowledge, prepared.manifest(), prepared.grant(),
                prepared.approvalHash(), prepared.drafts()).execute();
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

    static PublicKey publicKey(byte[] bytes) {
        try {
            var match = PUBLIC_KEY.matcher(new String(bytes, StandardCharsets.US_ASCII));
            if (!match.matches()) throw new SecurityException("PUBLICATION_PUBLIC_KEY_INVALID");
            byte[] encoded = Base64.getDecoder().decode(match.group(1).replaceAll("\\s", ""));
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
        } catch (Exception exception) {
            throw new SecurityException("PUBLICATION_PUBLIC_KEY_INVALID");
        }
    }
}
