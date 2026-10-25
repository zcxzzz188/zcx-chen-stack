package com.zcx.chenstack.websocket;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zcx.chenstack.domain.constants.BlogConstants;
import com.zcx.chenstack.domain.dto.WebSocketMessage;
import com.zcx.chenstack.domain.entity.Photo;
import com.zcx.chenstack.domain.entity.PrivateMessage;
import com.zcx.chenstack.domain.enums.ExamineStatusEnum;
import com.zcx.chenstack.domain.enums.WebSocketMessageTypeEnum;
import com.zcx.chenstack.domain.result.AuditResult;
import com.zcx.chenstack.mapper.PhotoMapper;
import com.zcx.chenstack.service.ConversationService;
import com.zcx.chenstack.service.PrivateMessageService;
import com.zcx.chenstack.utils.TextAuditUtils;
import com.zcx.chenstack.utils.XssUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;

/**
 * WebSocket 消息处理器
 * 处理私信相关的 WebSocket 消息
 */
@Component
@Slf4j
public class PrivateMessageWebSocketHandler extends TextWebSocketHandler {

    @Resource
    private WebSocketSessionManager sessionManager;

    @Resource
    private PrivateMessageService privateMessageService;

    @Resource
    private ConversationService conversationService;

    @Resource
    private PhotoMapper photoMapper;

    @Resource
    private TextAuditUtils textAuditUtils;

    /**
     * WebSocket 连接建立后的处理
     * 1. 注册用户会话到会话管理器
     * 2. 广播用户上线状态给所有相关用户
     */
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Integer userId = (Integer) session.getAttributes().get("userId");
        if (userId == null) {
            log.warn("WebSocket 连接建立时 userId 为 null，关闭连接");
            try {
                session.close();
            } catch (IOException e) {
                log.error("关闭 WebSocket 连接失败", e);
            }
            return;
        }
        sessionManager.registerSession(userId, session);

        // 广播用户上线状态
        sessionManager.broadcastUserOnlineStatus(userId, true);
    }

    /**
     * 处理接收到的 WebSocket 文本消息
     * 根据消息类型分发到不同的处理方法：
     * - SEND_MESSAGE: 发送私信
     * - READ_MESSAGE: 标记消息已读
     * - REVOKE_MESSAGE: 撤回消息
     * - HEARTBEAT: 心跳保活
     */
    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            Integer fromUserId = (Integer) session.getAttributes().get("userId");
            if (fromUserId == null) {
                log.warn("WebSocket 消息处理时 userId 为 null");
                return;
            }
            String payload = message.getPayload();

            // 解析消息 {"type": "SEND_MESSAGE","content": "你好","toUserId": 123}
            WebSocketMessage wsMessage = JSON.parseObject(payload, WebSocketMessage.class);
            String type = wsMessage.getType();

            if (WebSocketMessageTypeEnum.SEND_MESSAGE.getType().equals(type)) {
                // 发送消息
                handleSendMessage(session, fromUserId, wsMessage);
            } else if (WebSocketMessageTypeEnum.READ_MESSAGE.getType().equals(type)) {
                // 标记消息已读
                handleReadMessage(fromUserId, wsMessage);
            } else if (WebSocketMessageTypeEnum.REVOKE_MESSAGE.getType().equals(type)) {
                // 撤回消息
                handleRevokeMessage(fromUserId, wsMessage);
            } else if (WebSocketMessageTypeEnum.HEARTBEAT.getType().equals(type)) {
                // 心跳保活
                handleHeartbeat(session);
            } else if (WebSocketMessageTypeEnum.TYPING.getType().equals(type)) {
                // 正在输入
                handleTyping(fromUserId, wsMessage);
            } else {
                log.warn("未知的消息类型: {}", type);
            }
        } catch (Exception e) {
            log.error("处理 WebSocket 消息异常", e);
        }
    }

    /**
     * WebSocket 连接关闭后的处理
     * 1. 广播用户下线状态给所有相关用户（在移除会话前广播）
     * 2. 从会话管理器中移除用户会话
     * 3. 清除 Redis 中的在线状态
     */
    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Integer userId = (Integer) session.getAttributes().get("userId");
        if (userId == null) {
            log.warn("WebSocket 连接关闭时 userId 为 null");
            return;
        }

        if (sessionManager.removeSession(userId, session)) {
            sessionManager.broadcastUserOnlineStatus(userId, false);
        }
    }

    /**
     * 处理 WebSocket 传输异常
     * 记录异常日志，便于排查问题
     */
    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        Integer userId = session != null ? (Integer) session.getAttributes().get("userId") : null;
        log.error("WebSocket 传输异常，用户ID: {}, sessionId: {}", userId, session != null ? session.getId() : "null", exception);
    }

    /**
     * 处理发送消息
     */
    private void handleSendMessage(WebSocketSession session, Integer fromUserId, WebSocketMessage wsMessage) {
        Integer toUserId = wsMessage.getToUserId();
        String content = wsMessage.getContent();
        Integer messageType = wsMessage.getMessageType();
        String imageUrl = wsMessage.getImageUrl();
        Integer examineStatus = ExamineStatusEnum.WAIT.getCode();
        String ackMessage = "审核中";

        // XSS 过滤：防止恶意脚本注入
        content = XssUtils.cleanPlainText(content);

        if (toUserId == null || toUserId <= 0
                || (Integer.valueOf(2).equals(messageType) && (imageUrl == null || imageUrl.isBlank()))) {
            sendMessage(session, WebSocketMessage.error("私信接收者或图片参数无效"));
            return;
        }

        // 图片先保存待审核，保存后再次读取审核结果，覆盖审核早于消息入库的情况。
        if (!Integer.valueOf(2).equals(messageType)) {
            if (content == null || content.trim().isEmpty()) {
                sendMessage(session, WebSocketMessage.error("私信内容不能为空"));
                return;
            }

            try {
                AuditResult auditResult = textAuditUtils.auditTextWithDetailsSplit(content);
                if (auditResult != null && auditResult.getStatus() != null) {
                    examineStatus = auditResult.getStatus();
                }
                if (ExamineStatusEnum.PASS.getCode().equals(examineStatus)) {
                    ackMessage = "发送成功";
                } else if (ExamineStatusEnum.NO_PASS.getCode().equals(examineStatus)) {
                    ackMessage = auditResult != null && auditResult.getErrorMessage() != null
                            ? auditResult.getErrorMessage()
                            : "私信内容未通过审核";
                }
            } catch (Exception e) {
                log.error("私信文本审核异常，fromUserId={}, toUserId={}", fromUserId, toUserId, e);
                examineStatus = ExamineStatusEnum.WAIT.getCode();
                ackMessage = "私信内容需要调整或稍后重试";
            }
        }

        // 1. 保存消息到数据库
        PrivateMessage privateMessage = new PrivateMessage();
        privateMessage.setFromUserId(fromUserId);
        privateMessage.setToUserId(toUserId);
        privateMessage.setContent(content);
        privateMessage.setMessageType(messageType);
        privateMessage.setImageUrl(imageUrl);
        privateMessage.setExamineStatus(examineStatus);
        privateMessage.setIsRead(0);
        privateMessage.setIsRevoked(0);
        if (!privateMessageService.save(privateMessage)) {
            sendMessage(session, WebSocketMessage.error("私信保存失败，请稍后重试"));
            return;
        }

        // 回执使用已持久化的身份、ID及内容，前端无需猜测对应的临时消息。
        if (session != null && session.isOpen()) {
            WebSocketMessage ackResponse = WebSocketMessage.builder()
                    .type(WebSocketMessageTypeEnum.SEND_SUCCESS.getType())
                    .messageId(privateMessage.getId())
                    .fromUserId(fromUserId)
                    .toUserId(toUserId)
                    .content(content)
                    .messageType(messageType)
                    .imageUrl(imageUrl)
                    .createTime(privateMessage.getCreateTime())
                    .code(examineStatus)
                    .message(ackMessage)
                    .build();
            sendMessage(session, ackResponse);
        }
        if (Integer.valueOf(2).equals(messageType)) {
            privateMessageService.syncImageAuditStatus(List.of(imageUrl), resolveMessageImageAuditStatus(imageUrl));
        } else if (ExamineStatusEnum.PASS.getCode().equals(examineStatus)) {
            privateMessageService.deliverApprovedMessage(privateMessage);
        }
    }

    /**
     * 处理已读消息
     */
    private void handleReadMessage(Integer userId, WebSocketMessage wsMessage) {
        Integer targetUserId = wsMessage.getTargetUserId();

        // REST 与 WebSocket 共用已读入口，未读数由尚未读的已审消息计算。
        conversationService.clearUnreadCount(userId, targetUserId);

        // 通知发送者消息已读
        WebSocketSession targetSession = sessionManager.getSession(targetUserId);
        if (targetSession != null && targetSession.isOpen()) {
            WebSocketMessage readNotify = WebSocketMessage.builder()
                    .type(WebSocketMessageTypeEnum.MESSAGE_READ.getType())
                    .fromUserId(userId)
                    .build();
            sendMessage(targetSession, readNotify);
        }
    }

    /**
     * 处理撤回消息
     */
    private void handleRevokeMessage(Integer userId, WebSocketMessage wsMessage) {
        Integer messageId = wsMessage.getMessageId();
        WebSocketSession fromSession = sessionManager.getSession(userId);

        try {
            // 撤回消息
            PrivateMessage message = privateMessageService.revokeMessage(messageId, userId);

            if (message != null) {
                Integer toUserId = message.getToUserId();

                // 更新双方的会话，将最后一条消息内容设置为 "撤回了一条消息"
                conversationService.updateLastMessageAfterRevoke(userId, toUserId, BlogConstants.RevokedMessage);

                // 通知发送者撤回成功
                if (fromSession != null && fromSession.isOpen()) {
                    WebSocketMessage successMessage = WebSocketMessage.builder()
                            .type(WebSocketMessageTypeEnum.REVOKE_SUCCESS.getType())
                            .messageId(messageId)
                            .build();
                    sendMessage(fromSession, successMessage);
                }

                // 通知接收者消息被撤回
                WebSocketSession toSession = sessionManager.getSession(toUserId);
                if (toSession != null && toSession.isOpen()) {
                    WebSocketMessage revokeNotify = WebSocketMessage.builder()
                            .type(WebSocketMessageTypeEnum.MESSAGE_REVOKED.getType())
                            .messageId(messageId)
                            .fromUserId(userId)
                            .toUserId(toUserId)
                            .content(BlogConstants.RevokedMessage) // 撤回提示文本
                            .build();
                    sendMessage(toSession, revokeNotify);
                }
            }
        } catch (Exception e) {
            log.error("撤回消息失败: messageId={}, userId={}", messageId, userId, e);
            // 通知发送者撤回失败
            if (fromSession != null && fromSession.isOpen()) {
                WebSocketMessage errorMessage = WebSocketMessage.builder()
                        .type(WebSocketMessageTypeEnum.REVOKE_FAILED.getType())
                        .messageId(messageId)
                        .message(e.getMessage())
                        .build();
                sendMessage(fromSession, errorMessage);
            }
        }
    }

    /**
     * 处理心跳
     */
    private void handleHeartbeat(WebSocketSession session) {
        sendMessage(session, WebSocketMessage.heartbeat());
    }

    /**
     * 处理正在输入通知
     */
    private void handleTyping(Integer fromUserId, WebSocketMessage wsMessage) {
        Integer toUserId = wsMessage.getToUserId();

        // 推送输入状态给接收者（如果在线）
        WebSocketSession toSession = sessionManager.getSession(toUserId);
        if (toSession != null && toSession.isOpen()) {
            WebSocketMessage pushMessage = WebSocketMessage.builder()
                    .type(WebSocketMessageTypeEnum.TYPING_NOTIFY.getType())
                    .fromUserId(fromUserId)
                    .toUserId(toUserId)
                    .build();
            sendMessage(toSession, pushMessage);
        }
    }

    /**
     * 获取图片消息当前对应的审核状态
     *
     * @param imageUrl 图片地址
     * @return 审核状态，默认待审核
     */
    private Integer resolveMessageImageAuditStatus(String imageUrl) {
        if (imageUrl == null || imageUrl.trim().isEmpty()) {
            return ExamineStatusEnum.WAIT.getCode();
        }

        Photo photo = photoMapper.selectOne(new LambdaQueryWrapper<Photo>()
                .eq(Photo::getUrl, imageUrl)
                .orderByDesc(Photo::getId)
                .last("LIMIT 1"));
        if (photo == null || photo.getExamineStatus() == null) {
            return ExamineStatusEnum.WAIT.getCode();
        }
        return photo.getExamineStatus();
    }

    /**
     * 发送消息
     */
    private void sendMessage(WebSocketSession session, Object message) {
        try {
            String json = JSON.toJSONString(message);
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException e) {
            log.error("发送 WebSocket 消息失败", e);
        }
    }

}
