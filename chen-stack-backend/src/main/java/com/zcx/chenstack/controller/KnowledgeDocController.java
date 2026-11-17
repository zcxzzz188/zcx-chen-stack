package com.zcx.chenstack.controller;

import com.zcx.chenstack.domain.result.Result;
import com.zcx.chenstack.knowledge.KnowledgeAnalysisService;
import com.zcx.chenstack.knowledge.KnowledgeDocException;
import com.zcx.chenstack.knowledge.KnowledgeDocRetrievalService;
import com.zcx.chenstack.knowledge.KnowledgeDocService;
import com.zcx.chenstack.knowledge.KnowledgeDocStore;
import com.zcx.chenstack.knowledge.KnowledgeImportDraftService;
import com.zcx.chenstack.knowledge.KnowledgeTextParser;
import com.zcx.chenstack.utils.SecurityUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一知识文档管理端接口。
 *
 * <p>上传 ≠ 审核通过 ≠ 索引成功：接口分别返回 {@code importStatus}、{@code reviewStatus}、
 * {@code indexStatus}，前端必须分列展示，不得合并为单一“状态”。</p>
 */
@ConditionalOnProperty(prefix = "chen.knowledge", name = "enabled", havingValue = "true")
@RestController
@RequestMapping("/admin/knowledge")
public class KnowledgeDocController {
    private final KnowledgeDocService service;
    private final KnowledgeDocRetrievalService retrieval;
    private final KnowledgeAnalysisService analysis;
    private final KnowledgeImportDraftService importDrafts;
    private final com.zcx.chenstack.knowledge.KnowledgeDocRegistry registry;

    public KnowledgeDocController(KnowledgeDocService service, KnowledgeDocRetrievalService retrieval,
                                  KnowledgeAnalysisService analysis, KnowledgeImportDraftService importDrafts,
                                  com.zcx.chenstack.knowledge.KnowledgeDocRegistry registry) {
        this.service = service;
        this.retrieval = retrieval;
        this.analysis = analysis;
        this.importDrafts = importDrafts;
        this.registry = registry;
    }

    public record DocumentView(long id, String sourceKey, String documentKind, String title, String summary,
                               String visibility, String reviewStatus, String publishStatus,
                               Integer currentVersionNo, String contentSha256, String originRef,
                               String createdAt, String updatedAt) {
    }

    public record VersionView(long id, int versionNo, String versionLabel, String state,
                              boolean bodyAvailable, String bodyProvenance, String normalizedSha256,
                              Integer charLength, String rawFormat, Integer reviewedBy, String reviewedAt,
                              String publishedAt, String legacyStateRaw) {
    }

    public record ImportView(String requestId, String originalFilename, String contentType, long fileBytes,
                             String fileSha256, boolean bomPresent, String status, Integer parsedChars,
                             String parserVersion, Long documentId, Long versionId, String createdAt) {
    }

    public record IndexTaskView(String requestId, String status, String operation, String collection,
                                int chunkCount, int expectedEmbeddingCalls, int embeddingRequests,
                                String fingerprintSha256, String errorCode, String completedAt) {
    }

    public record StatusView(boolean enabled, boolean indexerAvailable, String collection, String bucket,
                             long revision, String embeddingModel, int publishedDocuments,
                             int publishedChunks, List<String> titles) {
    }

    public record CandidateView(String candidateId, String title, String content, List<String> keywords,
                                String sourceSection, String sourceExcerpt, Double confidence,
                                List<String> reviewHints, String reviewStatus) {
    }

    public record AnalyzeView(String requestId, String originalFilename, String title, long sourceDocumentId,
                              long sourceVersionId, String reviewStatus, String model,
                              List<CandidateView> candidates, boolean inputTruncated,
                              boolean candidatesTruncated, String warning) {
    }

    public record DraftBatchRequest(String batchId,
                                    List<KnowledgeImportDraftService.CandidateInput> candidates) {
    }

    // ------------------------------------------------------------------ 状态

    @GetMapping("/status")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:read')")
    public Result<StatusView> status() {
        Map<String, Object> diagnostics = service.diagnostics();
        var catalog = registry.current();
        return Result.success(new StatusView(
                Boolean.TRUE.equals(diagnostics.get("enabled")),
                Boolean.TRUE.equals(diagnostics.get("indexer")),
                String.valueOf(diagnostics.get("collection")),
                String.valueOf(diagnostics.get("bucket")),
                ((Number) diagnostics.get("revision")).longValue(),
                String.valueOf(diagnostics.get("embeddingModel")),
                catalog.documentCount(), catalog.chunkCount(), catalog.titles()));
    }

    // ------------------------------------------------------------------ 文档

    @GetMapping("/documents")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:read')")
    public Result<List<DocumentView>> documents(@RequestParam(defaultValue = "") String q,
                                                @RequestParam(defaultValue = "100") int limit) {
        return Result.success(service.documents(q, limit).stream().map(KnowledgeDocController::view).toList());
    }

    @GetMapping("/documents/{id}")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:read')")
    public Result<DocumentView> document(@PathVariable long id) {
        return Result.success(view(service.document(id)));
    }

    @GetMapping("/documents/{id}/versions")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:read')")
    public Result<List<VersionView>> versions(@PathVariable long id) {
        return Result.success(service.versions(id).stream().map(KnowledgeDocController::view).toList());
    }

    @GetMapping("/documents/{id}/index-tasks")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:read')")
    public Result<List<IndexTaskView>> indexTasks(@PathVariable long id) {
        return Result.success(service.indexTasks(id).stream().map(KnowledgeDocController::view).toList());
    }

    @GetMapping("/documents/{id}/audit")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:read')")
    public Result<List<Map<String, Object>>> audit(@PathVariable long id) {
        return Result.success(service.audit(id));
    }

    @PostMapping("/documents/{id}/publish")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<Map<String, Object>> publish(@PathVariable long id) {
        KnowledgeDocService.PublishResult result = service.publish(id, SecurityUtils.getUserId());
        return Result.success(publishView(result));
    }

    @PostMapping("/documents/{id}/reindex")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<IndexTaskView> reindex(@PathVariable long id) {
        return Result.success(view(service.index(id, SecurityUtils.getUserId())));
    }

    @PostMapping("/documents/{id}/withdraw")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<DocumentView> withdraw(@PathVariable long id) {
        return Result.success(view(service.withdraw(id, SecurityUtils.getUserId())));
    }

    @DeleteMapping("/documents/{id}")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<DocumentView> revoke(@PathVariable long id) {
        return Result.success(view(service.revoke(id, SecurityUtils.getUserId())));
    }

    // ------------------------------------------------------------------ 导入

    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<ImportView> upload(@RequestParam("file") MultipartFile file,
                                     @RequestParam(value = "title", required = false) String title,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                     @RequestParam(value = "requestId", required = false) String requestId) {
        String key = firstNonBlank(idempotencyKey, requestId);
        if (key == null) {
            throw KnowledgeDocException.bad("IMPORT_REQUEST_ID_REQUIRED", "必须提供 Idempotency-Key");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException error) {
            throw KnowledgeDocException.bad("IMPORT_READ_FAILED", "读取上传文件失败");
        }
        return Result.success(view(service.upload(key, file.getOriginalFilename(), bytes, title,
                SecurityUtils.getUserId())));
    }

    @GetMapping("/imports")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:read')")
    public Result<List<ImportView>> imports(@RequestParam(defaultValue = "") String status,
                                            @RequestParam(defaultValue = "100") int limit) {
        return Result.success(service.imports(status, limit).stream().map(KnowledgeDocController::view).toList());
    }

    @GetMapping("/imports/{requestId}")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:read')")
    public Result<ImportView> importTask(@PathVariable String requestId) {
        return Result.success(view(service.importTask(requestId)));
    }

    /** 最近上传且仍可继续分析的导入任务；不含已驳回或已删除任务。 */
    @GetMapping("/imports/recent")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<List<KnowledgeAnalysisService.RecentImport>> recentImports(
            @RequestParam(defaultValue = "10") int limit) {
        return Result.success(analysis.recentImports(limit));
    }

    /** 恢复最近一次分析快照；没有记录时返回 NOT_ANALYZED，失败时返回 FAILED 与错误码。 */
    @GetMapping("/imports/{requestId}/analysis")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<KnowledgeAnalysisService.StoredAnalysis> storedAnalysis(@PathVariable String requestId) {
        return Result.success(analysis.latest(requestId));
    }

    /** 唯一原件下载协议：后端鉴权后流式返回，不返回任何存储直链或预签名 URL。 */
    @GetMapping("/imports/{requestId}/content")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public ResponseEntity<byte[]> download(@PathVariable String requestId) {
        KnowledgeDocStore.ImportRow task = service.importTask(requestId);
        byte[] bytes = service.readRaw(task.objectKey());
        String encoded = URLEncoder.encode(task.originalFilename(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encoded)
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(task.contentType()))
                .contentLength(bytes.length)
                .body(bytes);
    }

    @PostMapping("/imports/{requestId}/approve")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<DocumentView> approve(@PathVariable String requestId) {
        return Result.success(view(service.approve(requestId, SecurityUtils.getUserId())));
    }

    @PostMapping("/imports/{requestId}/reject")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<Void> reject(@PathVariable String requestId,
                               @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.get("reasonCode");
        service.reject(requestId, reason, SecurityUtils.getUserId());
        return Result.success(null);
    }

    // ------------------------------------------------------------------ 预览（管理端）

    @GetMapping("/imports/{requestId}/preview")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:read')")
    public Result<Map<String, Object>> preview(@PathVariable String requestId) {
        KnowledgeDocStore.ImportRow task = service.importTask(requestId);
        byte[] bytes = service.readRaw(task.objectKey());
        KnowledgeTextParser.Parsed parsed = KnowledgeTextParser.parse(task.originalFilename(), bytes, null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("title", parsed.title());
        result.put("rawFormat", parsed.rawFormat());
        result.put("bomPresent", parsed.bomPresent());
        result.put("rawPreview", KnowledgeTextParser.preview(parsed.rawText(), 4000));
        result.put("normalizedPreview", KnowledgeTextParser.preview(parsed.normalizedText(), 4000));
        result.put("normalizedChars", parsed.normalizedText().length());
        return Result.success(result);
    }

    // ------------------------------------------------------------------ AI 候选知识

    /**
     * 对已上传文件做 AI 分析，返回待确认候选；不写草稿、不审核、不发布。
     * 失败返回 503 + {@code AI_ANALYSIS_*}，文件与导入任务保留。
     */
    @PostMapping("/imports/{requestId}/analyze")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<AnalyzeView> analyze(@PathVariable String requestId) {
        KnowledgeAnalysisService.Analysis result = analysis.analyze(requestId, SecurityUtils.getUserId());
        List<CandidateView> candidates = result.candidates().stream()
                .map(candidate -> new CandidateView(candidate.candidateId(), candidate.title(),
                        candidate.content(), candidate.keywords(), candidate.sourceSection(),
                        candidate.sourceExcerpt(), candidate.confidence(), candidate.reviewHints(),
                        candidate.reviewStatus()))
                .toList();
        return Result.success(new AnalyzeView(result.requestId(), result.originalFilename(), result.title(),
                result.sourceDocumentId(), result.sourceVersionId(), result.reviewStatus(), result.model(),
                candidates, result.inputTruncated(), result.candidatesTruncated(), result.warning()));
    }

    /** 管理员确认后，把所选候选保存到原有知识草稿箱；重复提交按候选 originRef 幂等。 */
    @PostMapping("/imports/{requestId}/drafts")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<KnowledgeImportDraftService.Result> saveDrafts(@PathVariable String requestId,
                                                                 @RequestBody DraftBatchRequest body) {
        if (body == null) {
            throw KnowledgeDocException.bad("DRAFT_BATCH_INVALID", "保存批次不能为空");
        }
        return Result.success(importDrafts.save(requestId, body.batchId(), body.candidates(),
                SecurityUtils.getUserId()));
    }

    // ------------------------------------------------------------------ 映射

    static DocumentView view(KnowledgeDocStore.DocRow row) {
        return new DocumentView(row.id(), row.sourceKey(), row.documentKind(), row.title(), row.summary(),
                row.visibility(), row.reviewStatus(), row.publishStatus(), row.currentVersionNo(),
                row.contentSha256(), row.originRef(),
                row.createdAt() == null ? "" : row.createdAt().toString(),
                row.updatedAt() == null ? "" : row.updatedAt().toString());
    }

    static VersionView view(KnowledgeDocStore.VersionRow row) {
        return new VersionView(row.id(), row.versionNo(), row.versionLabel(), row.state(), row.bodyAvailable(),
                row.bodyProvenance(), row.normalizedSha256(), row.charLength(), row.rawFormat(), row.reviewedBy(),
                row.reviewedAt() == null ? "" : row.reviewedAt().toString(),
                row.publishedAt() == null ? "" : row.publishedAt().toString(), row.legacyStateRaw());
    }

    static ImportView view(KnowledgeDocStore.ImportRow row) {
        return new ImportView(row.requestId(), row.originalFilename(), row.contentType(), row.fileBytes(),
                row.fileSha256(), row.bomPresent(), row.status(), row.parsedChars(), row.parserVersion(),
                row.documentId(), row.versionId(),
                row.createdAt() == null ? "" : row.createdAt().toString());
    }

    static IndexTaskView view(KnowledgeDocStore.IndexTaskRow row) {
        return new IndexTaskView(row.requestId(), row.status(), row.operation(), row.targetCollection(),
                row.chunkCount(), row.expectedEmbeddingCalls(), row.embeddingRequests(),
                row.fingerprintSha256(), row.lastErrorCode(),
                row.completedAt() == null ? "" : row.completedAt().toString());
    }

    private static Map<String, Object> publishView(KnowledgeDocService.PublishResult result) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("document", view(result.document()));
        view.put("indexed", result.indexed());
        view.put("indexStatus", result.indexTask() == null ? "NOT_STARTED" : result.indexTask().status());
        view.put("indexTaskId", result.indexTask() == null ? null : result.indexTask().requestId());
        view.put("indexError", result.indexErrorCode());
        return view;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }
}
