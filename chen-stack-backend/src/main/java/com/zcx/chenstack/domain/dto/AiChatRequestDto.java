package com.zcx.chenstack.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 智能客服聊天请求
 */
@Data
public class AiChatRequestDto {

    @NotBlank(message = "消息内容不能为空")
    @Size(max = 1000, message = "消息内容不能超过1000字符")
    private String message;

    @NotBlank(message = "会话ID不能为空")
    @Size(max = 128, message = "会话ID不能超过128字符")
    private String chatId;

    @Pattern(regexp = "[A-Za-z0-9_-]{1,64}", message = "请求ID无效")
    private String requestId;

    @Size(max = 400, message = "来源路径过长")
    private String originPath;

    @NotNull(message = "客服模式不能为空")
    @Pattern(regexp = "chat|rag", message = "仅支持chat或rag模式")
    private String mode = "chat";
}
