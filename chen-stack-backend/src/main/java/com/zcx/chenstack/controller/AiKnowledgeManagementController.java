package com.zcx.chenstack.controller;

import com.zcx.chenstack.domain.result.Result;
import com.zcx.chenstack.rag.NormalAiUsage;
import com.zcx.chenstack.service.AiKnowledgeManagementService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin")
public class AiKnowledgeManagementController {
    public record Test(@NotBlank @Size(max = 500) String query, @NotBlank @Pattern(regexp = "rag|wiki") String mode) {}
    public record Update(@NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,72}") String requestId,
                         @Min(0) long expectedGeneration, @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedContentHash,
                         @NotBlank @Size(max = 80) String title, @NotBlank @Size(max = 20000) String text,
                         @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{1,40}") String version,
                         @NotNull @Size(max = 20) List<@NotBlank @Size(max = 30) String> keywords,
                         @AssertTrue boolean confirmReviewed) {}
    public record Revoke(@NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,72}") String requestId,
                         @Min(0) long expectedGeneration, @NotBlank @Pattern(regexp = "[a-f0-9]{64}") String expectedContentHash,
                         @AssertTrue boolean confirmRevocation) {}
    public record Resume(@AssertTrue boolean confirmRecovery) {}
    public record DraftRequest(@Size(max = 80) String draftId,
                               @NotBlank @Size(max = 80) String title, @NotBlank @Size(max = 20000) String text,
                               @NotBlank @Pattern(regexp = "[a-zA-Z0-9._-]{1,40}") String version,
                               @NotBlank @Size(max = 80) String sourceUrl,
                               @NotNull @Size(max = 20) List<@NotBlank @Size(max = 30) String> keywords) {}
    public record DraftPublish(@NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,72}") String requestId,
                               @Min(0) long expectedGeneration, @AssertTrue boolean confirmReviewed) {}
    private final AiKnowledgeManagementService management;
    private final ObjectProvider<NormalAiUsage> usage;
    public AiKnowledgeManagementController(AiKnowledgeManagementService management, ObjectProvider<NormalAiUsage> usage) {
        this.management = management; this.usage = usage;
    }
    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping("/ai-knowledge/status") public Result<AiKnowledgeManagementService.Status> status() { return Result.success(management.status()); }
    @GetMapping("/ai-knowledge/rag/documents")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:read')")
    public Result<AiKnowledgeManagementService.Page<AiKnowledgeManagementService.Document>> documents(
            @RequestParam(defaultValue = "") String q, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size) { return Result.success(management.documents(q, page, size)); }
    @GetMapping("/ai-knowledge/rag/documents/{id}")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:read')")
    public Result<AiKnowledgeManagementService.Document> document(@PathVariable String id) { return Result.success(management.document(id)); }
    @PutMapping("/ai-knowledge/rag/documents/{id}")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<com.zcx.chenstack.rag.RagKnowledgeAdministration.Result> update(@PathVariable String id, @Valid @RequestBody Update request) {
        return Result.success(management.update(id, request.title(), request.text(), request.version(), request.keywords(),
                request.expectedGeneration(), request.expectedContentHash(), request.requestId()));
    }
    @PostMapping("/ai-knowledge/rag/documents/{id}/revoke")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<com.zcx.chenstack.rag.RagKnowledgeAdministration.Result> revoke(@PathVariable String id, @Valid @RequestBody Revoke request) {
        return Result.success(management.revoke(id, request.expectedGeneration(), request.expectedContentHash(), request.requestId()));
    }
    @GetMapping("/ai-knowledge/rag/documents/{id}/versions")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:read')")
    public Result<List<AiKnowledgeManagementService.Version>> versions(@PathVariable String id) {
        return Result.success(management.versions(id));
    }
    @GetMapping("/ai-knowledge/rag/drafts")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<List<AiKnowledgeManagementService.Draft>> drafts(@RequestParam(defaultValue = "") String q) {
        return Result.success(management.drafts(q));
    }
    @PostMapping("/ai-knowledge/rag/drafts")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<AiKnowledgeManagementService.Draft> createDraft(@Valid @RequestBody DraftRequest request) {
        return Result.success(management.createDraft(request.title(), request.text(), request.version(),
                request.sourceUrl(), request.keywords()));
    }
    @PutMapping("/ai-knowledge/rag/drafts/{id}")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<AiKnowledgeManagementService.Draft> updateDraft(@PathVariable String id, @Valid @RequestBody DraftRequest request) {
        return Result.success(management.updateDraft(id, request.title(), request.text(), request.version(),
                request.sourceUrl(), request.keywords()));
    }
    @DeleteMapping("/ai-knowledge/rag/drafts/{id}")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<Void> discardDraft(@PathVariable String id) {
        management.discardDraft(id);
        return Result.success();
    }
    @PostMapping("/ai-knowledge/rag/drafts/{id}/publish")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<com.zcx.chenstack.rag.RagKnowledgeAdministration.Result> publishDraft(@PathVariable String id,
                                                                                        @Valid @RequestBody DraftPublish request) {
        return Result.success(management.publishDraft(id, request.requestId(), request.expectedGeneration(), request.confirmReviewed()));
    }
    @GetMapping("/ai-knowledge/rag/operations/pending")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<List<AiKnowledgeManagementService.PendingOperation>> pendingOperations() { return Result.success(management.pendingOperations()); }
    @PostMapping("/ai-knowledge/rag/operations/{operationId}/resume")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<com.zcx.chenstack.rag.RagKnowledgeAdministration.Result> resume(@PathVariable String operationId, @Valid @RequestBody Resume request) {
        return Result.success(management.resume(operationId));
    }
    @PostMapping("/ai-knowledge/test")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:knowledge:test')")
    public Result<AiKnowledgeManagementService.Search> test(@Valid @RequestBody Test request) {
        return Result.success(management.test(request.query(), request.mode()));
    }
    @GetMapping("/ai/knowledge-usage")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:knowledge:test')")
    public Result<List<Map<String, Object>>> knowledgeUsage(@RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 50) throw new com.zcx.chenstack.wiki.WikiProductException(400, "查询数量应为1至50");
        NormalAiUsage ledger = usage.getIfAvailable();
        try { return Result.success(ledger == null ? List.of() : ledger.recentKnowledgeUses(limit)); }
        catch (SecurityException unavailable) {
            throw new com.zcx.chenstack.wiki.WikiProductException(503, "知识使用记录暂不可用，请稍后重试");
        }
    }
}
