package com.zcx.chenstack.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum MailEnum {

    REGISTER("register", "辰栈 注册", "register", "用户注册"),
    RESET_PASSWORD("resetPassword","辰栈 重置密码", "reset-password","重置密码"),
    RESET_EMAIL("resetEmail","辰栈 重置邮箱", "reset-email","重置邮箱"),
    EXAMINE("examine","辰栈 审核通知", "examine","审核通知"),
    BLACKLIST_NOTIFICATION("blacklistNotification","辰栈 黑名单通知", "blacklist-notification","黑名单通知"),
    COMMENT_NOTIFICATION("commentNotification","辰栈 评论通知", "comment-notification","评论通知"),
    SYSTEM_NOTIFICATION("systemNotification","辰栈 系统通知", "system-notification","系统通知");

    // 邮件类型
    private final String type;
    // 邮箱主题
    private final String subject;
    // 模板名称
    private final String templateName;
    // 描述
    private final String desc;
}
