package com.zcx.chenstack.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PublicationManifestTest {
    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String GIT = "7".repeat(40);
    private static final String ARTIFACT = "a".repeat(64);
    private static final String SNAPSHOT = "b".repeat(64);
    // 冻结发布范围；夹具正文均为合成内容，不替代正式知识。
    private static final List<String> IDS = List.of(
            "help-web-entry", "help-register", "help-login", "help-register-password", "help-password-reset",
            "help-article-publish", "help-article-edit", "help-article-manage", "help-search", "help-comment",
            "help-favorite", "help-profile", "help-notifications", "help-ai-features", "help-column-create",
            "help-column-articles", "help-column-manage", "help-column-view", "help-article-draft",
            "help-article-visibility", "help-article-recycle", "help-comment-manage", "help-like", "help-follow",
            "help-favorite-manage", "help-password-change", "help-email-change", "help-email-settings",
            "help-private-message", "help-private-revoke", "help-private-conversation", "help-creator-dashboard",
            "help-upload", "help-ai-tools", "help-about-contact", "help-discover", "help-session-logout",
            "help-ai-quota", "help-search-limits");

    private KnowledgeDocument document(String id) {
        return new KnowledgeDocument(id, "合成帮助", "  合成说明\r\n第二行  ", "synthetic-1", "PLATFORM_HELP", "/",
                "NOT_PUBLISHED", "CONTENT_REVIEW_REQUIRED", Instant.EPOCH, List.of("合成"));
    }

    private List<KnowledgeDocument> drafts() {
        List<KnowledgeDocument> values = new ArrayList<>();
        IDS.forEach(id -> values.add(document(id)));
        values.add(document("help-history"));
        for (int i = 0; i < 12; i++) values.add(document("help-admin-synthetic-" + i));
        return values;
    }

    private Map<String, byte[]> resources(List<KnowledgeDocument> documents) throws Exception {
        return Map.of(PublicationManifest.PLATFORM_HELP_RESOURCE, JSON.writeValueAsBytes(documents),
                PublicationManifest.CAPABILITIES_RESOURCE, "{\"status\":\"synthetic\"}".getBytes(StandardCharsets.UTF_8));
    }

    private PublicationManifest manifest(Map<String, byte[]> resources) {
        Map<String, String> hashes = new LinkedHashMap<>();
        resources.forEach((path, bytes) -> hashes.put(path, PublicationManifest.sha256(bytes)));
        return new PublicationManifest(1, GIT, ARTIFACT, "isolated", "chen_synthetic_collection", "c".repeat(64),
                hashes, 4, SNAPSHOT, PublicationManifest.Operation.PUBLISH, null, false, false,
                IDS.stream().map(id -> new PublicationManifest.Topic(id, "synthetic-1",
                        KnowledgeCatalog.hash("合成说明\n第二行"))).toList());
    }

    private PublicationManifest.RuntimeIdentity runtime() {
        return new PublicationManifest.RuntimeIdentity(GIT, ARTIFACT, "isolated", "chen_synthetic_collection",
                "c".repeat(64), 4, SNAPSHOT);
    }

    private PublicationManifest change(PublicationManifest base, String field, Object value) throws Exception {
        var node = JSON.valueToTree(base);
        ((com.fasterxml.jackson.databind.node.ObjectNode) node).set(field, JSON.valueToTree(value));
        return PublicationManifest.parse(JSON.writeValueAsBytes(node));
    }

    @Test void validatesExactlyFrozen39WithoutPublishingTheDrafts() throws Exception {
        var resources = resources(drafts());
        var result = manifest(resources).validate(runtime(), resources);
        assertEquals(Set.copyOf(IDS), PublicationManifest.userSourceIds());
        assertEquals(39, result.size());
        assertTrue(result.stream().allMatch(d -> "NOT_PUBLISHED".equals(d.visibility())
                && "CONTENT_REVIEW_REQUIRED".equals(d.reviewStatus())));
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
    }

    @Test void rejectsMissingDuplicateUnknownAdminAndHistoryTopics() throws Exception {
        var resources = resources(drafts());
        var base = manifest(resources);
        var missing = new ArrayList<>(base.topics()); missing.removeFirst();
        assertThrows(SecurityException.class, () -> change(base, "topics", missing).validate(runtime(), resources));
        for (String id : List.of(IDS.get(1), "help-new-unknown", "help-admin-synthetic-0", "help-history")) {
            var topics = new ArrayList<>(base.topics());
            topics.set(0, new PublicationManifest.Topic(id, "synthetic-1", "a".repeat(64)));
            assertThrows(SecurityException.class, () -> change(base, "topics", topics).validate(runtime(), resources));
        }
    }

    @Test void rejectsWrongVersionAndCanonicalBodyHash() throws Exception {
        var resources = resources(drafts()); var base = manifest(resources);
        for (var topic : List.of(new PublicationManifest.Topic(IDS.getFirst(), "other-version", base.topics().getFirst().textSha256()),
                new PublicationManifest.Topic(IDS.getFirst(), "synthetic-1", "a".repeat(64)))) {
            var topics = new ArrayList<>(base.topics()); topics.set(0, topic);
            assertThrows(SecurityException.class, () -> change(base, "topics", topics).validate(runtime(), resources));
        }
    }

    @Test void bindsActualBuildEnvironmentCollectionAndEmbeddingIdentity() throws Exception {
        var resources = resources(drafts()); var base = manifest(resources);
        for (var field : Map.of("gitCommit", "8".repeat(40), "artifactSha256", "c".repeat(64), "environment", "other",
                "collection", "chen_other_collection", "embeddingSignature", "d".repeat(64)).entrySet()) {
            assertThrows(SecurityException.class, () -> change(base, field.getKey(), field.getValue()).validate(runtime(), resources));
        }
    }

    @Test void requiresExactResourceSetAndRawBytesIncludingWhitespace() throws Exception {
        var resources = new LinkedHashMap<>(resources(drafts())); var base = manifest(resources);
        byte[] original = resources.get(PublicationManifest.PLATFORM_HELP_RESOURCE);
        byte[] whitespaceChange = Arrays.copyOf(original, original.length + 1);
        whitespaceChange[whitespaceChange.length - 1] = '\n';
        resources.put(PublicationManifest.PLATFORM_HELP_RESOURCE, whitespaceChange);
        assertThrows(SecurityException.class, () -> base.validate(runtime(), resources));
        resources.put(PublicationManifest.PLATFORM_HELP_RESOURCE, original);
        resources.remove(PublicationManifest.CAPABILITIES_RESOURCE);
        assertThrows(SecurityException.class, () -> base.validate(runtime(), resources));
        resources.put("other/path", new byte[]{1});
        assertThrows(SecurityException.class, () -> base.validate(runtime(), resources));
    }

    @Test void rejectsDraftIdentityOrPublicationStatusChangesEvenWithReboundResourceHash() throws Exception {
        for (String field : List.of("sourceId", "visibility", "reviewStatus")) {
            var documents = JSON.valueToTree(drafts());
            ((com.fasterxml.jackson.databind.node.ObjectNode) documents.get(0)).put(field,
                    field.equals("sourceId") ? "help-new-unknown" : field.equals("visibility") ? "PUBLIC" : "DOCUMENT_REVIEWED");
            var resources = new LinkedHashMap<>(resources(drafts()));
            resources.put(PublicationManifest.PLATFORM_HELP_RESOURCE, JSON.writeValueAsBytes(documents));
            assertThrows(SecurityException.class, () -> manifest(resources).validate(runtime(), resources));
        }
    }

    @Test void fullValidationUsesActualCatalogButContentValidationDoesNotPretendToCheckIt() throws Exception {
        var resources = resources(drafts()); var base = manifest(resources);
        var advanced = new PublicationManifest.RuntimeIdentity(GIT, ARTIFACT, "isolated", "chen_synthetic_collection",
                "c".repeat(64), 5, "c".repeat(64));
        assertThrows(SecurityException.class, () -> base.validate(advanced, resources));
        assertEquals(39, base.validateResources(advanced, resources).size());
    }

    @Test void emptySentinelRequiresExplicitInitializationAtZero() throws Exception {
        var resources = resources(drafts()); var base = manifest(resources);
        var empty = change(change(change(base, "expectedGeneration", 0), "expectedSnapshotSha256", "EMPTY"),
                "initializeEmptyCatalog", true);
        var runtime = new PublicationManifest.RuntimeIdentity(GIT, ARTIFACT, "isolated", "chen_synthetic_collection",
                "c".repeat(64), 0, "EMPTY");
        assertEquals(39, empty.validate(runtime, resources).size());
        assertThrows(SecurityException.class, () -> change(empty, "initializeEmptyCatalog", false).validate(runtime, resources));
        assertThrows(SecurityException.class, () -> change(empty, "expectedGeneration", 1).validateResources(runtime, resources));
        assertThrows(SecurityException.class, () -> change(empty, "expectedSnapshotSha256", SNAPSHOT).validateResources(runtime, resources));
    }

    @Test void revokeOnlyTargetsTheApproved39AndCannotInitializeOrCreateCollection() throws Exception {
        var resources = resources(drafts()); var base = manifest(resources);
        var revoke = change(change(base, "operation", "REVOKE"), "revokeSourceId", IDS.getFirst());
        assertEquals(39, revoke.validate(runtime(), resources).size());
        for (String id : List.of("help-history", "help-admin-synthetic-0", "help-unknown")) {
            assertThrows(SecurityException.class, () -> change(revoke, "revokeSourceId", id).validate(runtime(), resources));
        }
        assertThrows(SecurityException.class, () -> change(revoke, "createCollection", true).validate(runtime(), resources));
        assertThrows(SecurityException.class, () -> change(base, "revokeSourceId", IDS.getFirst()).validate(runtime(), resources));
    }

    @Test void rejectsUnknownDuplicateTrailingAndOversizedJson() {
        for (String json : List.of("{\"unexpected\":true}", "{\"schemaVersion\":1,\"schemaVersion\":1}", "{} {}", "null")) {
            assertThrows(SecurityException.class, () -> PublicationManifest.parse(json.getBytes(StandardCharsets.UTF_8)));
        }
        assertThrows(SecurityException.class, () -> PublicationManifest.parse(new byte[65_537]));
        assertThrows(SecurityException.class, () -> PublicationManifest.parse("{}".getBytes(StandardCharsets.UTF_16)));
        assertThrows(SecurityException.class, () -> PublicationManifest.parse(new byte[]{(byte) 0xc3, 0x28}));
    }

    @Test void currentClasspathStillContainsExactlyTheFrozen39UserCandidates() throws Exception {
        try (var input = getClass().getResourceAsStream("/" + PublicationManifest.PLATFORM_HELP_RESOURCE)) {
            assertNotNull(input);
            var documents = JSON.readValue(input, KnowledgeDocument[].class);
            assertEquals(52, documents.length);
            assertEquals(Set.copyOf(IDS), Arrays.stream(documents).map(KnowledgeDocument::sourceId)
                    .filter(id -> !id.startsWith("help-admin-") && !id.equals("help-history"))
                    .collect(java.util.stream.Collectors.toSet()));
            assertTrue(Arrays.stream(documents).allMatch(d -> "NOT_PUBLISHED".equals(d.visibility())
                    && "CONTENT_REVIEW_REQUIRED".equals(d.reviewStatus())));
        }
    }
}
