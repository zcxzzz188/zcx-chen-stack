package com.zcx.chenstack.knowledge;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档 → AI 候选知识的用例层。
 *
 * <p>分析只读取已上传的私有原件并调用模型，不创建草稿、不审核、不发布。失败时保留
 * 导入任务和原件，返回稳定的 {@code AI_ANALYSIS_*} 错误码供管理员重试或改用文字添加。</p>
 *
 * <p>候选快照写入现有 {@code knowledge_audit_log.detail_json}，不新增候选表，也不复制草稿系统。
 * 刷新页面、关闭弹窗或切换导入任务后，仍可恢复尚未保存的候选；草稿去重仍由
 * {@code origin_ref = imp:<requestId>:<candidateId>} 保证。</p>
 */
@ConditionalOnProperty(prefix = "chen.knowledge", name = "enabled", havingValue = "true")
@Service
public class KnowledgeAnalysisService {
    public record Analysis(String requestId, String originalFilename, String title, long sourceDocumentId,
                           long sourceVersionId, String reviewStatus, String model,
                           List<KnowledgeCandidateExtractor.Candidate> candidates,
                           boolean inputTruncated, boolean candidatesTruncated, String warning) {
        public Analysis {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    /** 供刷新/重进弹窗读取的候选快照；candidates 保持与 POST 分析相同的字段名。 */
    public record StoredAnalysis(String status, String requestId, String originalFilename, String title,
                                 Long sourceDocumentId, Long sourceVersionId, String reviewStatus, String model,
                                 List<Map<String, Object>> candidates, boolean inputTruncated,
                                 boolean candidatesTruncated, String warning, String errorCode,
                                 String errorMessage, String analyzedAt) {
        public StoredAnalysis {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    public record RecentImport(String requestId, String originalFilename, long fileBytes, Integer parsedChars,
                               String status, Long documentId, Long versionId, String createdAt,
                               String analysisStatus, String analysisErrorCode, int candidateCount) {
    }

    private final KnowledgeDocService documents;
    private final KnowledgeCandidateExtractor extractor;

    public KnowledgeAnalysisService(KnowledgeDocService documents, KnowledgeCandidateExtractor extractor) {
        this.documents = documents;
        this.extractor = extractor;
    }

    public Analysis analyze(String requestId, Integer actorId) {
        if (actorId == null || actorId <= 0) {
            throw KnowledgeDocException.denied();
        }
        KnowledgeDocStore.ImportRow task = documents.importTask(requestId);
        if (!List.of("PARSED", "APPROVED", "PUBLISHED").contains(task.status())) {
            throw KnowledgeDocException.conflict("IMPORT_STATE_INVALID",
                    "当前导入任务状态不能进行知识分析");
        }
        SourceResult source = source(task, actorId);
        task = documents.importTask(requestId);
        byte[] raw = documents.readRaw(task.objectKey());
        KnowledgeTextParser.Parsed parsed = KnowledgeTextParser.parse(task.originalFilename(), raw, null);
        try {
            KnowledgeCandidateExtractor.Extraction extraction = extractor.extract(
                    task.originalFilename(), parsed.title(), parsed.normalizedText());
            Analysis analysis = new Analysis(requestId, task.originalFilename(), parsed.title(),
                    source.documentId(), source.versionId(), KnowledgeCandidateExtractor.REVIEW_PENDING,
                    extraction.model(), extraction.candidates(), extraction.inputTruncated(),
                    extraction.candidatesTruncated(), warning(extraction));
            auditCompleted(task, analysis, actorId);
            return analysis;
        } catch (KnowledgeDocException failure) {
            auditFailed(task, failure.code(), failure.getMessage(), actorId);
            throw failure;
        }
    }

    /**
     * 最近一次分析状态与候选快照。没有分析记录时返回 {@code NOT_ANALYZED}，
     * 有失败记录时返回 {@code FAILED} 与可读错误码，供管理员重试。
     */
    public StoredAnalysis latest(String requestId) {
        KnowledgeDocStore.ImportRow task = documents.importTask(requestId);
        return documents.store().latestImportAnalysis(requestId)
                .map(analysis -> fromSnapshot(task, analysis))
                .orElseGet(() -> new StoredAnalysis("NOT_ANALYZED", task.requestId(),
                        task.originalFilename(), KnowledgeTextParser.baseNameOf(task.originalFilename()),
                        task.documentId(), task.versionId(), null, null, List.of(),
                        false, false, "", null, null, null));
    }

    /** 文档上传弹窗的“最近导入任务”；只返回仍可用于分析的 PARSED 任务。 */
    public List<RecentImport> recentImports(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), 20);
        List<RecentImport> result = new ArrayList<>();
        for (KnowledgeDocStore.ImportRow task : documents.imports("PARSED", safeLimit)) {
            KnowledgeDocStore.ImportAnalysis analysis = documents.store()
                    .latestImportAnalysis(task.requestId()).orElse(null);
            String analysisStatus = analysis == null ? "NOT_ANALYZED" : analysis.status();
            String errorCode = analysis == null ? null : analysis.errorCode();
            int candidateCount = analysis == null ? 0 : candidateCount(analysis.resultJson());
            result.add(new RecentImport(task.requestId(), task.originalFilename(), task.fileBytes(),
                    task.parsedChars(), task.status(), task.documentId(), task.versionId(),
                    task.createdAt() == null ? "" : task.createdAt().toString(), analysisStatus,
                    errorCode, candidateCount));
        }
        return result;
    }

    private StoredAnalysis fromSnapshot(KnowledgeDocStore.ImportRow task, KnowledgeDocStore.ImportAnalysis analysis) {
        String analyzedAt = analysis.analyzedAt() == null ? "" : analysis.analyzedAt().toString();
        if ("FAILED".equals(analysis.status())) {
            return new StoredAnalysis("FAILED", task.requestId(), task.originalFilename(),
                    KnowledgeTextParser.baseNameOf(task.originalFilename()), task.documentId(),
                    task.versionId(), null, analysis.model(), List.of(), false, false, "",
                    analysis.errorCode(), analysis.errorMessage(), analyzedAt);
        }
        JSONObject detail = parseDetail(analysis.resultJson());
        if (detail == null) {
            return new StoredAnalysis("COMPLETED", task.requestId(), task.originalFilename(),
                    KnowledgeTextParser.baseNameOf(task.originalFilename()), task.documentId(),
                    task.versionId(), KnowledgeCandidateExtractor.REVIEW_PENDING, analysis.model(), List.of(),
                    false, false, "", null, null, analyzedAt);
        }
        List<Map<String, Object>> candidates = new ArrayList<>();
        JSONArray array = detail.getJSONArray("candidates");
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                JSONObject candidate = array.getJSONObject(i);
                if (candidate != null) {
                    candidates.add(new LinkedHashMap<>(candidate));
                }
            }
        }
        return new StoredAnalysis("COMPLETED", task.requestId(), task.originalFilename(),
                detail.getString("title"), detail.getLong("sourceDocumentId") == null
                        ? task.documentId() : detail.getLong("sourceDocumentId"),
                detail.getLong("sourceVersionId") == null
                        ? task.versionId() : detail.getLong("sourceVersionId"),
                detail.getString("reviewStatus"), analysis.model(), candidates,
                detail.getBooleanValue("inputTruncated"), detail.getBooleanValue("candidatesTruncated"),
                detail.getString("warning"), null, null, analyzedAt);
    }

    private void auditCompleted(KnowledgeDocStore.ImportRow task, Analysis analysis, int actorId) {
        int candidateCount = analysis.candidates().size();
        try {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("title", analysis.title());
            snapshot.put("sourceDocumentId", analysis.sourceDocumentId());
            snapshot.put("sourceVersionId", analysis.sourceVersionId());
            snapshot.put("reviewStatus", analysis.reviewStatus());
            snapshot.put("inputTruncated", analysis.inputTruncated());
            snapshot.put("candidatesTruncated", analysis.candidatesTruncated());
            snapshot.put("warning", analysis.warning());
            List<Map<String, Object>> candidates = new ArrayList<>(candidateCount);
            for (KnowledgeCandidateExtractor.Candidate candidate : analysis.candidates()) {
                candidates.add(candidateMap(candidate));
            }
            snapshot.put("candidates", candidates);
            documents.store().recordAnalysis(task.requestId(), "COMPLETED", analysis.model(),
                    documents.store().writeJson(snapshot), null, null);
            audit(task, analysis.model(), candidateCount, actorId,
                    "AI_ANALYSIS_COMPLETED", null, null);
        } catch (RuntimeException unavailable) {
            // 快照写入失败不改变本次分析响应，但刷新后无法恢复；管理员可重新分析。
            org.slf4j.LoggerFactory.getLogger(KnowledgeAnalysisService.class)
                    .warn("AI分析完成快照写入失败：{}", unavailable.getClass().getSimpleName());
        }
    }

    private void auditFailed(KnowledgeDocStore.ImportRow task, String code, String message, int actorId) {
        try {
            documents.store().recordAnalysis(task.requestId(), "FAILED", extractor.modelId(),
                    null, code, message);
            audit(task, extractor.modelId(), 0, actorId, "AI_ANALYSIS_FAILED", code, message);
        } catch (RuntimeException unavailable) {
            org.slf4j.LoggerFactory.getLogger(KnowledgeAnalysisService.class)
                    .warn("AI分析失败审计写入失败：{}", unavailable.getClass().getSimpleName());
        }
    }

    /**
     * 审计只留操作事实：谁在何时对哪个导入任务做了分析、结果与错误码、候选条数。
     * 候选正文不再写入审计，避免审计承担可恢复业务状态。
     */
    private void audit(KnowledgeDocStore.ImportRow task, String model, int candidateCount, int actorId,
                       String reasonCode, String errorCode, String message) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("model", model);
        detail.put("candidateCount", candidateCount);
        if (errorCode != null) {
            detail.put("errorCode", errorCode);
            detail.put("message", message == null ? "" : message);
        }
        documents.store().audit(task.requestId(), task.documentId(), task.versionId(), task.requestId(),
                actorId, KnowledgeDocStore.ACTOR_USER, null, "UPDATE", "IMPORT", null, null,
                reasonCode, detail);
    }

    private static Map<String, Object> candidateMap(KnowledgeCandidateExtractor.Candidate candidate) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("candidateId", candidate.candidateId());
        map.put("title", candidate.title());
        map.put("content", candidate.content());
        map.put("keywords", candidate.keywords());
        map.put("sourceSection", candidate.sourceSection());
        map.put("sourceExcerpt", candidate.sourceExcerpt());
        map.put("confidence", candidate.confidence());
        map.put("reviewHints", candidate.reviewHints());
        map.put("reviewStatus", candidate.reviewStatus());
        return map;
    }

    private static int candidateCount(String detailJson) {
        JSONObject detail = parseDetail(detailJson);
        if (detail == null) return 0;
        JSONArray array = detail.getJSONArray("candidates");
        return array == null ? 0 : array.size();
    }

    private static JSONObject parseDetail(String detailJson) {
        if (detailJson == null || detailJson.isBlank()) return null;
        try {
            return JSON.parseObject(detailJson);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private SourceResult source(KnowledgeDocStore.ImportRow task, int actorId) {
        KnowledgeDocService.SourceDocument source = documents.ensureSourceDocument(task.requestId(), actorId);
        return new SourceResult(source.documentId(), source.versionId());
    }

    private record SourceResult(long documentId, long versionId) {
    }

    private static String warning(KnowledgeCandidateExtractor.Extraction extraction) {
        if (extraction.inputTruncated() && extraction.candidatesTruncated()) {
            return "原文与候选数量均超过上限，已截断；请重点核对。";
        }
        if (extraction.inputTruncated()) {
            return "原文超过分析上限，仅分析了部分正文；请重点核对。";
        }
        if (extraction.candidatesTruncated()) {
            return "候选知识超过单次上限，仅保留前面的条目；可调整文档后重新分析。";
        }
        return "";
    }
}
