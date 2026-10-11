package com.zcx.chenstack.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
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
}
