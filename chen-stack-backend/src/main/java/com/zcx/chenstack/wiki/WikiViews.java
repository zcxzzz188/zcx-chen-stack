package com.zcx.chenstack.wiki;

import java.util.List;

/** Explicit HTTP projections: never serialize source raw JSON or database rows. */
public final class WikiViews {
    private WikiViews() {}
    public record Page<T>(List<T> items, long total, int page, int size, List<String> topics) {}
    public record Source(String type, String id, String title, String text, String fingerprint,
                         String originVersion, String parserVersion, Long snapshotId, Long generation, boolean active) {}
    public record Job(String requestId, String state, String createdAt, String model, Integer maxCalls,
                      long usedCalls, Long maxUnits, long usedUnits, String firstStop, List<String> topics) {}
    public record Draft(String requestId, String markdown, String bodyHash, String manifestHash, String compilerVersion,
                        String createdAt, List<Source> sources, List<String> topics, List<String> conflicts) {}
    public record Revision(long id, long pageId, int version, String requestId, String title, String summary,
                           String markdown, String state, String bodyHash, String manifestHash, String createdAt, boolean current) {}
    public record ManagedPage(long id, String title, Integer currentVersion, boolean withdrawn, boolean publicEligible) {}
    public record Card(long id, String title, String summary, List<String> topics, int version, String publishedAt) {}
    public record Citation(String title, String url, String version) {}
    public record PublishedPage(long id, String title, String markdown, int version, String publishedAt,
                                List<String> topics, List<Citation> citations, List<Card> related) {}
}
