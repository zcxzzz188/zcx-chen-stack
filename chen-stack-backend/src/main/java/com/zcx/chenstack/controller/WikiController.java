package com.zcx.chenstack.controller;

import com.zcx.chenstack.domain.result.Result;
import com.zcx.chenstack.knowledge.KnowledgePages;
import com.zcx.chenstack.knowledge.KnowledgeDocException;
import com.zcx.chenstack.wiki.WikiProductException;
import com.zcx.chenstack.wiki.WikiPublicationService;
import com.zcx.chenstack.wiki.WikiViews.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wiki/pages")
public class WikiController {
    private final ObjectProvider<WikiPublicationService> publication;
    private final boolean portalEnabled;
    @Autowired(required = false)
    private KnowledgePages library;

    public WikiController(ObjectProvider<WikiPublicationService> publication,
                          @Value("${chen.wiki.public-portal-enabled:${WIKI_PUBLIC_PORTAL_ENABLED:false}}") boolean portalEnabled) {
        this.publication = publication;
        this.portalEnabled = portalEnabled;
    }

    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping(params = "portalStatus") public Result<Boolean> portalStatus() {
        return Result.success(portalEnabled && (library != null || publication.getIfAvailable() != null));
    }
    @GetMapping(params = "!portalStatus") public Result<Page<Card>> list(@RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String topic,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="12") int size) {
        return Result.success(portalEnabled && library != null ? library.publicList(q, page, size) : publicPortal().list(q, topic, page, size));
    }
    @GetMapping("/{id}") public Result<PublishedPage> read(@PathVariable long id) {
        return Result.success(portalEnabled && library != null ? library.publicPage(id) : publicPortal().read(id));
    }

    @ExceptionHandler(KnowledgeDocException.class)
    public ResponseEntity<Result<Void>> handle(KnowledgeDocException error) {
        return new KnowledgeDocExceptionHandler().handle(error);
    }

    private WikiPublicationService publicPortal() {
        WikiPublicationService service = portalEnabled ? publication.getIfAvailable() : null;
        if (service == null) throw new WikiProductException(404, "Wiki页面不可用");
        return service;
    }
}
