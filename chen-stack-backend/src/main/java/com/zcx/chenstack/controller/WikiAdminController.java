package com.zcx.chenstack.controller;

import com.zcx.chenstack.domain.result.Result;
import com.zcx.chenstack.wiki.*;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

import static com.zcx.chenstack.wiki.WikiViews.*;

@RestController
@RequestMapping("/admin/wiki")
@PreAuthorize("@adminSecurity.isAdmin()")
@ConditionalOnProperty(prefix = "chen.wiki", name = {"enabled", "product.enabled"}, havingValue = "true")
public class WikiAdminController {
    public record SourceRequest(@NotNull WikiFoundation.SourceType type, @NotBlank @Size(max=80) String id) {
        WikiFoundation.SourceKey key() { return new WikiFoundation.SourceKey(type, id); }
    }
    public record Authorization(@NotNull WikiFoundation.SourceType type, @NotBlank @Size(max=80) String id,
                                @NotNull @Pattern(regexp="[a-f0-9]{64}") String fingerprint) {
        WikiFoundation.SourceKey key() { return new WikiFoundation.SourceKey(type, id); }
    }
    public record Compile(@NotBlank @Pattern(regexp="[A-Za-z0-9_.:-]{1,80}") String requestId,
                          @NotEmpty @Size(max=16) List<@NotNull @Positive Long> snapshotIds,
                          @Size(max=16) Set<@NotBlank @Size(max=100) String> topics) {}
    public record CreateRevision(@NotBlank @Size(max=80) String requestId, @Positive Long pageId,
                                 @NotBlank @Size(max=160) String title, @NotBlank @Size(max=500) String summary,
                                 @Size(max=900000) String markdown) {}
    public record Hash(@NotNull @Pattern(regexp="[a-f0-9]{64}") String bodyHash) {}
    public record Approval(@NotNull @Pattern(regexp="[a-f0-9]{64}") String bodyHash, boolean confirmEvidence, boolean confirmConflicts) {}
    public record Publish(@NotNull @Pattern(regexp="[a-f0-9]{64}") String bodyHash, @Positive Integer expectedCurrentVersion) {}
    public record Current(@Positive Integer expectedCurrentVersion) {}
    public record Restore(@Positive long revisionId, @Positive Integer expectedCurrentVersion) {}
    private final WikiManagementService management;
    private final WikiPublicationService publication;
    public WikiAdminController(WikiManagementService management, WikiPublicationService publication) {
        this.management = management; this.publication = publication;
    }
    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping("/access") public Result<Boolean> access() { return Result.success(true); }
    @GetMapping("/sources") public Result<Page<Source>> sources(@RequestParam(defaultValue="") String q,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="12") int size) {
        return Result.success(management.sources(q, page, size));
    }
    @PostMapping("/sources/inspect") public Result<Source> inspect(@Valid @RequestBody SourceRequest request) { return Result.success(management.inspect(request.key())); }
    @PostMapping("/sources/authorize") public Result<Source> authorize(@Valid @RequestBody Authorization request) { return Result.success(management.authorize(request.key(), request.fingerprint())); }
    @PostMapping("/sources/revoke") public Result<Void> revoke(@Valid @RequestBody SourceRequest request) { management.revoke(request.key()); return Result.success(); }
    @GetMapping("/jobs") public Result<Page<Job>> jobs(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="12") int size) { return Result.success(management.jobs(page, size)); }
    @PostMapping("/jobs") public Result<Job> compile(@Valid @RequestBody Compile request) { return Result.success(management.createJob(request.requestId(), request.snapshotIds(), request.topics())); }
    @PostMapping("/jobs/{id}/cancel") public Result<Void> cancel(@PathVariable String id) { management.cancel(id); return Result.success(); }
    @GetMapping("/jobs/{id}/draft") public Result<Draft> draft(@PathVariable String id) { return Result.success(management.draft(id)); }
    @GetMapping("/affected") public Result<List<WikiCompilationService.RecompileTarget>> affected(@RequestParam WikiFoundation.SourceType type, @RequestParam String id) {
        return Result.success(management.affected(new WikiFoundation.SourceKey(type, id)));
    }
    @GetMapping("/pages") public Result<List<ManagedPage>> pages() { return Result.success(publication.managedPages()); }
    @GetMapping("/pages/{id}/versions") public Result<List<Revision>> versions(@PathVariable long id) { return Result.success(publication.versions(id)); }
    @PostMapping("/revisions") public Result<Revision> create(@Valid @RequestBody CreateRevision request) {
        return Result.success(publication.create(request.requestId(), request.pageId(), request.title(), request.summary(), request.markdown()));
    }
    @PostMapping("/revisions/{id}/submit") public Result<Revision> submit(@PathVariable long id, @Valid @RequestBody Hash request) { return Result.success(publication.submit(id, request.bodyHash())); }
    @PostMapping("/revisions/{id}/approve") public Result<Revision> approve(@PathVariable long id, @Valid @RequestBody Approval request) {
        return Result.success(publication.approve(id, request.bodyHash(), request.confirmEvidence(), request.confirmConflicts()));
    }
    @PostMapping("/revisions/{id}/publish") public Result<Revision> publish(@PathVariable long id, @Valid @RequestBody Publish request) {
        return Result.success(publication.publish(id, request.bodyHash(), request.expectedCurrentVersion()));
    }
    @PostMapping("/pages/{id}/withdraw") public Result<Void> withdraw(@PathVariable long id, @Valid @RequestBody Current request) {
        publication.withdraw(id, request.expectedCurrentVersion()); return Result.success();
    }
    @PostMapping("/pages/{id}/restore") public Result<Revision> restore(@PathVariable long id, @Valid @RequestBody Restore request) {
        return Result.success(publication.restore(id, request.revisionId(), request.expectedCurrentVersion()));
    }
}
