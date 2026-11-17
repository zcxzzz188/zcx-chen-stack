package com.zcx.chenstack.controller;

import com.zcx.chenstack.domain.result.Result;
import com.zcx.chenstack.knowledge.KnowledgeDocException;
import com.zcx.chenstack.knowledge.KnowledgeLibrary;
import com.zcx.chenstack.knowledge.KnowledgeList;
import com.zcx.chenstack.knowledge.KnowledgePages;
import com.zcx.chenstack.knowledge.KnowledgeSearch;
import com.zcx.chenstack.knowledge.KnowledgeSettings;
import com.zcx.chenstack.knowledge.KnowledgeTextParser;
import com.zcx.chenstack.security.AdminSecurity;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/admin/knowledge-library")
@ConditionalOnProperty(prefix = "chen.knowledge.simple", name = "enabled", havingValue = "true")
public class KnowledgeLibraryController {
    private final KnowledgeLibrary library;
    private final KnowledgePages pages;
    private final KnowledgeSearch search;
    private final KnowledgeSettings settings;
    private final AdminSecurity security;

    public KnowledgeLibraryController(KnowledgeLibrary library, KnowledgePages pages,
                                      KnowledgeSearch search, KnowledgeSettings settings, AdminSecurity security) {
        this.library = library;
        this.pages = pages;
        this.search = search;
        this.settings = settings;
        this.security = security;
    }

    public record EntryInput(@NotBlank @Size(max = 160) String title, @NotBlank @Size(max = 20000) String content) {}
    public record PageInput(@NotBlank @Size(max = 160) String title, @NotBlank @Size(max = 500) String summary,
                            @NotBlank @Size(max = 20000) String content) {}
    public record PageSources(@NotEmpty @Size(max = 5) List<@NotNull @Positive Long> ids) {}
    public record Toggle(@NotNull Boolean enabled, LocalDateTime expectedUpdatedAt) {}
    public record ArchiveInput(@NotEmpty @Size(max = 10) List<@NotNull @Positive Long> ids) {}
    public record Capabilities(boolean ragManage, boolean wikiEdit, boolean wikiCompile, boolean wikiPublish,
                               boolean configManage) {}
    public record Verification(@NotBlank @Size(max = 1000) String question,
                               @NotNull @Pattern(regexp = "rag|wiki|hybrid") String mode, boolean answer) {}
    public record ReplyStyleInput(@NotNull @Pattern(regexp = "PROFESSIONAL_CONCISE|FRIENDLY_NATURAL|PATIENT_DETAILED") String replyStyle) {}
    public record RestorePage(@NotNull LocalDateTime expectedUpdatedAt) {}

    @GetMapping("/capabilities")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:read') or @adminSecurity.hasKnowledgePermission('ai:wiki:read') or @adminSecurity.hasKnowledgePermission('ai:config:read')")
    public Result<Capabilities> capabilities() {
        return Result.success(new Capabilities(security.hasKnowledgePermission("ai:rag:manage"),
                security.hasKnowledgePermission("ai:wiki:edit"), security.hasKnowledgePermission("ai:wiki:compile"),
                security.hasKnowledgePermission("ai:wiki:publish"), security.hasKnowledgePermission("ai:config:manage")));
    }

    @GetMapping("/documents/list")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:read')")
    public Result<KnowledgeList<KnowledgeLibrary.Original>> documentList(@Valid @ModelAttribute KnowledgeList.Filter filter,
            @RequestParam(required = false) Boolean curated,
            @RequestParam(required = false) @Pattern(regexp = "PENDING|READY|FAILED|UNINDEXED") String state) {
        return Result.success(library.documents(filter, curated, state));
    }

    @PostMapping(value = "/documents/archive", produces = "application/zip")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:read')")
    public ResponseEntity<byte[]> downloadArchive(@Valid @RequestBody ArchiveInput input) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=knowledge-originals.zip")
                .header("X-Content-Type-Options", "nosniff")
                .body(library.downloadArchive(input.ids()));
    }

    @GetMapping("/documents")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:read')")
    public Result<List<KnowledgeLibrary.Original>> documents(@RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset) {
        return Result.success(library.documents(offset));
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<Long> upload(@RequestParam MultipartFile file, @RequestParam(required = false) @Positive Long replaceId) throws IOException {
        if (file.getSize() > KnowledgeTextParser.MAX_FILE_BYTES) {
            throw KnowledgeDocException.bad("KNOWLEDGE_FILE_TOO_LARGE", "文件不得超过 2 MiB");
        }
        return Result.success(library.upload(file.getOriginalFilename(), file.getBytes(), replaceId));
    }

    @GetMapping("/documents/{id}/original")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:read')")
    public ResponseEntity<byte[]> original(@PathVariable @Positive long id, @RequestParam(defaultValue = "false") boolean download) {
        var file = library.download(id);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(new MediaType("text", "plain", StandardCharsets.UTF_8))
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.builder(download ? "attachment" : "inline")
                        .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
                .body(file.bytes());
    }

    @DeleteMapping("/documents/{id}")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<Void> deleteDocument(@PathVariable @Positive long id) {
        library.remove(id, true);
        return Result.success();
    }

    @PostMapping("/documents/{id}/retry")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<Void> retryDocument(@PathVariable @Positive long id) {
        library.retryDocument(id);
        return Result.success();
    }

    @GetMapping("/entries")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:read')")
    public Result<List<KnowledgeLibrary.Entry>> entries(@RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset,
            @RequestParam(required = false) @Positive Long documentId,
            @RequestParam(defaultValue = "") @Size(max = 160) String q) {
        return Result.success(library.entries(offset, documentId, q.strip()));
    }

    @GetMapping("/entries/list")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:read')")
    public Result<KnowledgeList<KnowledgeLibrary.Entry>> entryList(@Valid @ModelAttribute KnowledgeList.Filter filter,
            @RequestParam(required = false) @Positive Long documentId,
            @RequestParam(required = false) Boolean enabled) {
        return Result.success(library.entries(filter, documentId, enabled));
    }

    @GetMapping("/entries/{id}")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:read')")
    public Result<KnowledgeLibrary.Entry> entry(@PathVariable @Positive long id) {
        return Result.success(library.entry(id));
    }

    @PostMapping("/entries")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<Long> createEntry(@Valid @RequestBody EntryInput input) {
        return Result.success(library.save(null, input.title(), input.content()));
    }

    @PutMapping("/entries/{id}")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<Long> editEntry(@PathVariable @Positive long id, @Valid @RequestBody EntryInput input) {
        return Result.success(library.save(id, input.title(), input.content()));
    }

    @PutMapping("/entries/{id}/enabled")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<Void> enabled(@PathVariable @Positive long id, @Valid @RequestBody Toggle input) {
        library.setEnabled(id, input.enabled(), input.expectedUpdatedAt());
        return Result.success();
    }

    @PostMapping("/entries/{id}/retry")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<Void> retry(@PathVariable @Positive long id) {
        library.retry(id);
        return Result.success();
    }

    @DeleteMapping("/entries/{id}")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:rag:manage')")
    public Result<Void> deleteEntry(@PathVariable @Positive long id) {
        library.remove(id, false);
        return Result.success();
    }

    @GetMapping("/pages")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:wiki:read')")
    public Result<List<KnowledgePages.Page>> pages(@RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset) {
        return Result.success(pages.list(offset));
    }

    @GetMapping("/pages/list")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:wiki:read')")
    public Result<KnowledgeList<KnowledgePages.ListItem>> pageList(@Valid @ModelAttribute KnowledgeList.Filter filter,
            @RequestParam(required = false) @Pattern(regexp = "PUBLISHED|OFFLINE|DRAFT") String state,
            @RequestParam(required = false) Boolean reviewRequired) {
        return Result.success(pages.list(filter, state, reviewRequired));
    }

    @GetMapping("/pages/{id}")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:wiki:read')")
    public Result<KnowledgePages.Page> page(@PathVariable @Positive long id) {
        return Result.success(pages.read(id));
    }

    @GetMapping("/pages/{id}/sources")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:wiki:read')")
    public Result<List<KnowledgePages.PageSource>> pageSources(@PathVariable @Positive long id) {
        return Result.success(pages.sources(id));
    }

    @GetMapping("/pages/{id}/revisions")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:wiki:read')")
    public Result<List<KnowledgePages.Revision>> pageRevisions(@PathVariable @Positive long id) {
        return Result.success(pages.revisions(id));
    }

    @GetMapping("/pages/{id}/revisions/{version}")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:wiki:read')")
    public Result<KnowledgePages.Revision> pageRevision(@PathVariable @Positive long id, @PathVariable @Positive int version) {
        return Result.success(pages.revision(id, version));
    }

    @PostMapping("/pages/{id}/revisions/{version}/restore")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:wiki:edit') and @adminSecurity.hasKnowledgePermission('ai:wiki:publish')")
    public Result<Void> restorePage(@PathVariable @Positive long id, @PathVariable @Positive int version,
                                    @Valid @RequestBody RestorePage input) {
        pages.restore(id, version, input.expectedUpdatedAt());
        return Result.success();
    }

    @PutMapping("/pages/{id}")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:wiki:edit')")
    public Result<Void> editPage(@PathVariable @Positive long id, @Valid @RequestBody PageInput input) {
        pages.edit(id, input.title(), input.summary(), input.content());
        return Result.success();
    }

    @PostMapping("/pages")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:wiki:compile')")
    public Result<Long> generate(@Valid @RequestBody PageSources sources) {
        return Result.success(pages.generate(sources.ids()));
    }

    @PutMapping("/pages/{id}/published")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:wiki:publish')")
    public Result<Void> publish(@PathVariable @Positive long id, @Valid @RequestBody Toggle input) {
        pages.publish(id, input.enabled(), input.expectedUpdatedAt());
        return Result.success();
    }

    @DeleteMapping("/pages/{id}")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:wiki:publish')")
    public Result<Void> deletePage(@PathVariable @Positive long id) {
        pages.delete(id);
        return Result.success();
    }

    @PostMapping("/verification")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:knowledge:test')")
    public Result<KnowledgeSearch.Verification> verify(@Valid @RequestBody Verification input) {
        return Result.success(search.verify(input.question(), input.mode(), input.answer()));
    }

    @GetMapping("/settings")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:config:read')")
    public Result<KnowledgeSettings.Settings> settings() {
        return Result.success(settings.get());
    }

    @PutMapping("/settings")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:config:manage')")
    public Result<KnowledgeSettings.Settings> configure(@Valid @RequestBody KnowledgeSettings.Settings input) {
        return Result.success(settings.save(input));
    }

    @PutMapping("/settings/reply-style")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:config:manage')")
    public Result<KnowledgeSettings.Settings> configureReplyStyle(@Valid @RequestBody ReplyStyleInput input) {
        return Result.success(settings.saveReplyStyle(KnowledgeSettings.ReplyStyle.valueOf(input.replyStyle())));
    }

    @ExceptionHandler(KnowledgeDocException.class)
    public ResponseEntity<Result<Void>> handle(KnowledgeDocException error) {
        return new KnowledgeDocExceptionHandler().handle(error);
    }
}
