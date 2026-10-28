package com.zcx.chenstack.controller;

import com.zcx.chenstack.domain.result.Result;
import com.zcx.chenstack.wiki.WikiProductException;
import com.zcx.chenstack.wiki.WikiPublicationService;
import com.zcx.chenstack.wiki.WikiViews.*;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wiki/pages")
public class WikiController {
    private final ObjectProvider<WikiPublicationService> publication;
    private final boolean portalEnabled;

    public WikiController(ObjectProvider<WikiPublicationService> publication,
                          @Value("${chen.wiki.public-portal-enabled:${WIKI_PUBLIC_PORTAL_ENABLED:false}}") boolean portalEnabled) {
        this.publication = publication;
        this.portalEnabled = portalEnabled;
    }

    @ModelAttribute public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }
    @GetMapping(params = "portalStatus") public Result<Boolean> portalStatus() {
        return Result.success(portalEnabled && publication.getIfAvailable() != null);
    }
    @GetMapping(params = "!portalStatus") public Result<Page<Card>> list(@RequestParam(defaultValue="") String q, @RequestParam(defaultValue="") String topic,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="12") int size) { return Result.success(publicPortal().list(q, topic, page, size)); }
    @GetMapping("/{id}") public Result<PublishedPage> read(@PathVariable long id) { return Result.success(publicPortal().read(id)); }

    private WikiPublicationService publicPortal() {
        WikiPublicationService service = portalEnabled ? publication.getIfAvailable() : null;
        if (service == null) throw new WikiProductException(404, "Wiki页面不可用");
        return service;
    }
}
