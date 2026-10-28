package com.zcx.chenstack.wiki;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zcx.chenstack.rag.KnowledgeCatalog;
import com.zcx.chenstack.rag.ReleaseProviderBudget;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.Map;

/** 本地运维授权文件与独立累计账本；没有 HTTP 授权/扩额/重置接口。 */
public final class WikiProviderAuthorization {
    public static final int MAX_CALLS = 16;
    public static final int MAX_WIRE_BYTES = 16_384;
    public static final int INPUT_TOKEN_CEILING = 32_768;
    public static final int MAX_OUTPUT_TOKENS = 2048;
    public static final BigDecimal INPUT_USD_PER_MILLION = new BigDecimal("0.30");
    public static final BigDecimal OUTPUT_USD_PER_MILLION = new BigDecimal("1.20");
    public record Grant(int version, String budgetId, String ledgerPath, String endpoint, String model,
                        String expiresAt, int maxChatCalls, BigDecimal maxUsd, Map<String, String> manifests) { }
    private static final ObjectMapper JSON = new ObjectMapper();
    private final Path authorizationPath;
    private final String authorizationHash;
    private final Path ledgerPath;
    private final String budgetId;
    private final Path g2LedgerPath;
    private final String endpoint;
    private final String model;

    public WikiProviderAuthorization(String authorizationPath, String authorizationHash, String ledgerPath,
                                     String budgetId, String g2LedgerPath, URI endpoint, String model) {
        this.authorizationPath = path(authorizationPath);
        this.authorizationHash = authorizationHash == null ? "" : authorizationHash;
        this.ledgerPath = path(ledgerPath);
        this.budgetId = budgetId == null ? "" : budgetId;
        this.g2LedgerPath = path(g2LedgerPath);
        this.endpoint = endpoint.toString(); this.model = model;
    }

    public static BigDecimal reservedUsd(int calls) {
        return INPUT_USD_PER_MILLION.multiply(BigDecimal.valueOf(INPUT_TOKEN_CEILING))
                .add(OUTPUT_USD_PER_MILLION.multiply(BigDecimal.valueOf(MAX_OUTPUT_TOKENS)))
                .multiply(BigDecimal.valueOf(calls)).movePointLeft(6);
    }

    public Grant require(String requestId, String manifestHash) {
        try {
            if (authorizationPath == null || ledgerPath == null || !authorizationHash.matches("[a-fA-F0-9]{64}")
                    || !budgetId.matches("wiki-[A-Za-z0-9._-]{1,60}") || Files.exists(haltPath())) throw denied();
            if (Files.size(authorizationPath) > 65_536) throw denied();
            byte[] bytes = Files.readAllBytes(authorizationPath);
            if (!KnowledgeCatalog.hash(new String(bytes, StandardCharsets.UTF_8)).equalsIgnoreCase(authorizationHash)) throw denied();
            Grant grant = JSON.readValue(bytes, Grant.class);
            if (grant.version() != 1 || !budgetId.equals(grant.budgetId()) || !endpoint.equals(grant.endpoint())
                    || !model.equals(grant.model()) || !Instant.now().isBefore(Instant.parse(grant.expiresAt()))
                    || grant.maxChatCalls() < 1 || grant.maxChatCalls() > MAX_CALLS || grant.maxUsd() == null
                    || grant.maxUsd().compareTo(new BigDecimal("0.20")) > 0
                    || reservedUsd(grant.maxChatCalls()).compareTo(grant.maxUsd()) > 0
                    || grant.manifests() == null || grant.manifests().isEmpty() || grant.manifests().size() > 5
                    || !grant.manifests().containsKey(requestId)
                    || !grant.manifests().get(requestId).matches("[a-f0-9]{64}")
                    || manifestHash != null && !manifestHash.equals(grant.manifests().get(requestId))) throw denied();
            Path actual = ledgerPath.toRealPath();
            if (!actual.equals(Path.of(grant.ledgerPath()).toRealPath())
                    || g2LedgerPath != null && Files.exists(g2LedgerPath) && Files.isSameFile(actual, g2LedgerPath)) throw denied();
            // 初始16次以内的wiki独立header不能套用G2的20→100扩额事件。
            new ReleaseProviderBudget(actual.toString(), budgetId, 0, grant.maxChatCalls());
            return grant;
        } catch (SecurityException rejected) { throw rejected; }
        catch (Exception invalid) { throw denied(); }
    }

    public ReleaseProviderBudget budget(String requestId, String manifestHash) {
        Grant grant = require(requestId, manifestHash);
        return new ReleaseProviderBudget(ledgerPath.toString(), budgetId, 0, grant.maxChatCalls());
    }

    public void halt() {
        try {
            try (var channel = java.nio.channels.FileChannel.open(haltPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var bytes = java.nio.ByteBuffer.wrap("WIKI_PROVIDER_HALTED_RECONCILIATION_REQUIRED\n".getBytes(StandardCharsets.US_ASCII));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
        } catch (FileAlreadyExistsException retained) {
            // 保留第一次UNKNOWN；不提供自动删除、退款或恢复发送路径。
        } catch (java.io.IOException failed) { throw new SecurityException("WIKI_PROVIDER_HALT_PERSISTENCE_FAILED"); }
    }

    private Path haltPath() throws java.io.IOException {
        Path actual = ledgerPath.toRealPath();
        return actual.resolveSibling(actual.getFileName() + ".halt");
    }
    private static Path path(String value) {
        if (value == null || value.isBlank()) return null;
        Path path = Path.of(value);
        if (!path.isAbsolute()) throw new IllegalArgumentException("WIKI_PROVIDER_PATH_MUST_BE_ABSOLUTE");
        return path.normalize();
    }
    private static SecurityException denied() { return new SecurityException("WIKI_REAL_PROVIDER_NOT_AUTHORIZED"); }
}
