package com.zcx.chenstack.controller;

import com.zcx.chenstack.domain.result.Result;
import com.zcx.chenstack.service.AiRuntimeConfigurationService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** 管理员可视化配置小辰工作模式与每日额度；所有写入回源权限并持久化。 */
@RestController
@RequestMapping("/admin/ai-config")
public class AiRuntimeConfigurationController {
    /** chat 不再接受为新的工作模式；历史值仍由运行配置读取并展示。 */
    public record ModeRequest(@NotBlank @Pattern(regexp = "rag|wiki|hybrid") String mode) { }
    public record QuotaRequest(@Min(AiRuntimeConfigurationService.MIN_DAILY_LIMIT)
                               @Max(AiRuntimeConfigurationService.MAX_DAILY_LIMIT) int dailyLimit) { }
    public record View(AiRuntimeConfigurationService.Status status, boolean canManage) { }

    private final AiRuntimeConfigurationService configuration;
    private final com.zcx.chenstack.security.AdminSecurity security;

    public AiRuntimeConfigurationController(AiRuntimeConfigurationService configuration,
                                            com.zcx.chenstack.security.AdminSecurity security) {
        this.configuration = configuration;
        this.security = security;
    }

    private View view(AiRuntimeConfigurationService.Status status) {
        return new View(status, security.hasKnowledgePermission("ai:config:manage"));
    }

    @ModelAttribute
    public void noCache(HttpServletResponse response) { response.setHeader("Cache-Control", "no-store"); }

    @GetMapping
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:config:read')")
    public Result<View> status() {
        return Result.success(view(configuration.status()));
    }

    @PutMapping("/knowledge-mode")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:config:manage')")
    public Result<View> mode(@Valid @RequestBody ModeRequest request) {
        return Result.success(view(configuration.setMode(request.mode(), com.zcx.chenstack.utils.SecurityUtils.getUserId())));
    }

    @PutMapping("/daily-limit")
    @PreAuthorize("@adminSecurity.hasKnowledgePermission('ai:config:manage')")
    public Result<View> quota(@Valid @RequestBody QuotaRequest request) {
        return Result.success(view(configuration.setDailyLimit(request.dailyLimit(), com.zcx.chenstack.utils.SecurityUtils.getUserId())));
    }
}
