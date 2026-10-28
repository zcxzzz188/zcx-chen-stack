package com.zcx.chenstack.controller;

import com.zcx.chenstack.config.AiKnowledgeProperties;
import com.zcx.chenstack.domain.result.Result;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AiKnowledgeAdminController {
    public record Mode(String activeMode, List<String> implementedModes, List<String> notImplementedModes) { }
    private final AiKnowledgeProperties properties;

    public AiKnowledgeAdminController(AiKnowledgeProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/admin/ai/knowledge-mode")
    @PreAuthorize("@adminSecurity.isAdmin()")
    public Result<Mode> mode(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Result.success(new Mode(properties.knowledgeMode(), List.of("chat", "rag", "wiki"), List.of("hybrid", "auto")));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Result<Void>> denied() {
        Result<Void> result = Result.error("仅管理员可读取知识模式诊断");
        result.setCode(403);
        return ResponseEntity.status(403).header("Cache-Control", "no-store").body(result);
    }
}
