package com.zcx.chenstack.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zcx.chenstack.domain.constants.BlogConstants;
import com.zcx.chenstack.domain.dto.WebSocketMessage;
import com.zcx.chenstack.domain.entity.PrivateMessage;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.domain.enums.ExamineStatusEnum;
import com.zcx.chenstack.domain.enums.WebSocketMessageTypeEnum;
import com.zcx.chenstack.domain.vo.PageVo;
import com.zcx.chenstack.domain.vo.PrivateMessageVo;
import com.zcx.chenstack.exception.BlogException;
import com.zcx.chenstack.mapper.PrivateMessageMapper;
import com.zcx.chenstack.mapper.SysUserMapper;
import com.zcx.chenstack.service.PrivateMessageService;
import com.zcx.chenstack.service.ConversationService;
import com.zcx.chenstack.service.UserSettingsService;
import com.zcx.chenstack.utils.EmailUtils;
import com.zcx.chenstack.websocket.WebSocketSessionManager;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 私信服务实现类
 */
@Service
@Slf4j
public class PrivateMessageServiceImpl extends ServiceImpl<PrivateMessageMapper, PrivateMessage>
        implements PrivateMessageService {

    @Resource
    private PrivateMessageMapper privateMessageMapper;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private ConversationService conversationService;

    @Resource
    private WebSocketSessionManager sessionManager;

    @Resource
    private UserSettingsService userSettingsService;

    @Resource
    private EmailUtils emailUtils;

    @Override
    @Transactional
    public void syncImageAuditStatus(Collection<String> imageUrls, Integer examineStatus) {
        if (imageUrls == null || imageUrls.isEmpty()
                || (!ExamineStatusEnum.PASS.getCode().equals(examineStatus)
                && !ExamineStatusEnum.NO_PASS.getCode().equals(examineStatus))) {
            return;
        }
        boolean approved = ExamineStatusEnum.PASS.getCode().equals(examineStatus);
        LambdaQueryWrapper<PrivateMessage> query = new LambdaQueryWrapper<PrivateMessage>()
                .in(PrivateMessage::getImageUrl, imageUrls)
                .eq(PrivateMessage::getMessageType, 2).eq(PrivateMessage::getIsRevoked, 0);
        query.and(w -> w.eq(PrivateMessage::getExamineStatus, ExamineStatusEnum.WAIT.getCode())
                .or().isNull(PrivateMessage::getExamineStatus)
                .or(!approved).eq(!approved, PrivateMessage::getExamineStatus, ExamineStatusEnum.PASS.getCode()));
        for (PrivateMessage message : privateMessageMapper.selectList(query)) {
            LambdaUpdateWrapper<PrivateMessage> update = new LambdaUpdateWrapper<PrivateMessage>()
                    .eq(PrivateMessage::getId, message.getId())
                    .eq(PrivateMessage::getIsRevoked, 0)
                    .and(w -> w.eq(PrivateMessage::getExamineStatus, ExamineStatusEnum.WAIT.getCode())
                            .or().isNull(PrivateMessage::getExamineStatus)
                            .or(!approved).eq(!approved, PrivateMessage::getExamineStatus, ExamineStatusEnum.PASS.getCode()))
                    .set(PrivateMessage::getExamineStatus, examineStatus);
            // 发送补偿与异步审核竞争同一状态迁移，只有更新成功者投递。
            if (privateMessageMapper.update(null, update) != 1) {
                continue;
            }
            message.setExamineStatus(examineStatus);
            if (approved) {
                deliverApprovedMessage(message);
            }
            afterCommit(() -> sendImageAuditReceipt(message));
        }
    }

    @Override
    public void deliverApprovedMessage(PrivateMessage message) {
        if (!ExamineStatusEnum.PASS.getCode().equals(message.getExamineStatus())
                || !Integer.valueOf(0).equals(message.getIsRevoked())
                || Integer.valueOf(1).equals(message.getIsDeleted())) {
            return;
        }
        Integer fromUserId = message.getFromUserId();
        Integer toUserId = message.getToUserId();
        conversationService.updateConversation(fromUserId, toUserId, message);
        SysUser fromUser = sysUserMapper.selectById(fromUserId);
        SysUser toUser = sysUserMapper.selectById(toUserId);
        Integer unreadCount = conversationService.getUnreadCount(toUserId, fromUserId);
        WebSocketMessage push = messageEvent(message, WebSocketMessageTypeEnum.NEW_MESSAGE)
                .unreadCount(unreadCount).build();
        if (fromUser != null) {
            push.setFromUserNickname(fromUser.getNickname());
            push.setFromUserAvatar(fromUser.getAvatar());
        }
        if (toUser != null) {
            push.setToUserNickname(toUser.getNickname());
            push.setToUserAvatar(toUser.getAvatar());
        }
        afterCommit(() -> {
            sendToOnlineUser(toUserId, push);
            sendPrivateMessageEmailNotification(message, fromUser, toUser, unreadCount);
        });
    }

    private void sendImageAuditReceipt(PrivateMessage message) {
        sendToOnlineUser(message.getFromUserId(), messageEvent(message, WebSocketMessageTypeEnum.SEND_SUCCESS)
                .message(ExamineStatusEnum.PASS.getCode().equals(message.getExamineStatus())
                        ? "发送成功" : "图片未通过审核").build());
    }

    private static WebSocketMessage.WebSocketMessageBuilder messageEvent(PrivateMessage message,
            WebSocketMessageTypeEnum type) {
        return WebSocketMessage.builder().type(type.getType()).messageId(message.getId())
                .fromUserId(message.getFromUserId()).toUserId(message.getToUserId())
                .content(message.getContent()).messageType(message.getMessageType())
                .imageUrl(message.getImageUrl()).createTime(message.getCreateTime())
                .code(message.getExamineStatus());
    }

    private void sendToOnlineUser(Integer userId, WebSocketMessage message) {
        WebSocketSession session = sessionManager.getSession(userId);
        if (session != null && session.isOpen()) {
            sessionManager.sendMessage(userId, JSON.toJSONString(message));
        }
    }

    private void sendPrivateMessageEmailNotification(PrivateMessage message, SysUser fromUser,
            SysUser toUser, Integer unreadCount) {
        try {
            if (!Integer.valueOf(1).equals(userSettingsService.getReceivePrivateMessageEmail(message.getToUserId()))
                    || toUser == null || toUser.getEmail() == null || toUser.getEmail().trim().isEmpty()
                    || fromUser == null || unreadCount > 1) {
                return;
            }
            String content = Integer.valueOf(2).equals(message.getMessageType()) ? "[图片]" : message.getContent();
            String preview = content == null ? "" : content.length() > 50 ? content.substring(0, 50) + "..." : content;
            Map<String, Object> data = new HashMap<>();
            data.put("senderNickname", fromUser.getNickname());
            data.put("messagePreview", preview);
            data.put("receiveTime", message.getCreateTime());
            data.put("conversationUrl", "线上地址后续补充");
            emailUtils.sendHtmlMail(toUser.getEmail(), "来自 " + fromUser.getNickname() + " 的新私信",
                    "private-message-notification", data);
        } catch (Exception e) {
            log.error("发送私信邮件通知失败：toUserId={}", message.getToUserId(), e);
        }
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    @Override
    public PageVo<List<PrivateMessageVo>> getChatHistory(Integer userId, Integer targetUserId,
            Integer pageNum, Integer pageSize) {
        Page<PrivateMessage> page = new Page<>(pageNum, pageSize);

        LambdaQueryWrapper<PrivateMessage> qw = new LambdaQueryWrapper<PrivateMessage>()
                .and(wrapper -> wrapper
                        .and(w -> w.eq(PrivateMessage::getFromUserId, userId).eq(PrivateMessage::getToUserId,
                                targetUserId))
                        .or(w -> w.eq(PrivateMessage::getFromUserId, targetUserId).eq(PrivateMessage::getToUserId,
                                userId)
                                .and(w2 -> w2.eq(PrivateMessage::getExamineStatus, ExamineStatusEnum.PASS.getCode())
                                        .or()
                                        .isNull(PrivateMessage::getExamineStatus))))
                .eq(PrivateMessage::getIsRevoked, 0) // 过滤掉已撤回的消息
                .orderByDesc(PrivateMessage::getCreateTime);

        List<PrivateMessage> messages = privateMessageMapper.selectPage(page, qw).getRecords();

        if (messages.isEmpty()) {
            return new PageVo<>(new ArrayList<>(), 0L);
        }

        // 收集所有涉及的用户ID
        Set<Integer> userIds = new HashSet<>();
        messages.forEach(msg -> {
            userIds.add(msg.getFromUserId());
            userIds.add(msg.getToUserId());
        });

        // 批量查询用户信息
        List<SysUser> users = sysUserMapper.selectBatchIds(userIds);
        Map<Integer, SysUser> userMap = users.stream()
                .collect(Collectors.toMap(SysUser::getId, user -> user));

        // 转换为 VO 并填充用户信息
        List<PrivateMessageVo> messageVos = messages.stream().map(msg -> {
            PrivateMessageVo vo = BeanUtil.copyProperties(msg, PrivateMessageVo.class);

            // 从 Map 中获取用户信息
            SysUser fromUser = userMap.get(msg.getFromUserId());
            if (fromUser != null) {
                vo.setFromUserNickname(fromUser.getNickname());
                vo.setFromUserAvatar(fromUser.getAvatar());
            }

            SysUser toUser = userMap.get(msg.getToUserId());
            if (toUser != null) {
                vo.setToUserNickname(toUser.getNickname());
                vo.setToUserAvatar(toUser.getAvatar());
            }

            return vo;
        }).collect(Collectors.toList());

        return new PageVo<>(messageVos, page.getTotal());
    }

    @Override
    public void markAsRead(Integer fromUserId, Integer toUserId) {
        conversationService.clearUnreadCount(toUserId, fromUserId);
    }

    @Override
    public PrivateMessage revokeMessage(Integer messageId, Integer userId) {
        PrivateMessage message = privateMessageMapper.selectById(messageId);

        if (message == null) {
            throw new BlogException(BlogConstants.NotFoundPrivateMessage);
        }

        if (!message.getFromUserId().equals(userId)) {
            throw new BlogException(BlogConstants.CannotRevokeOthersMessage);
        }

        // 检查是否在2分钟内
        long diff = System.currentTimeMillis() - message.getCreateTime().getTime();
        if (diff > 2 * 60 * 1000) {
            throw new BlogException(BlogConstants.MessageRevokeTimeExpired);
        }

        if (message.getIsRevoked() == 1) {
            throw new BlogException(BlogConstants.MessageAlreadyRevoked);
        }

        // 更新为已撤回
        message.setIsRevoked(1);
        privateMessageMapper.updateById(message);

        return message;
    }

    @Override
    public void deleteMessage(Integer messageId, Integer userId) {
        PrivateMessage message = privateMessageMapper.selectById(messageId);

        if (message == null) {
            throw new BlogException(BlogConstants.NotFoundPrivateMessage);
        }

        // 只能删除自己发送或接收的消息
        if (!message.getFromUserId().equals(userId) && !message.getToUserId().equals(userId)) {
            throw new BlogException(BlogConstants.CannotRevokeOthersMessage);
        }

        // 使用 MyBatis-Plus 的逻辑删除
        privateMessageMapper.deleteById(message.getId());
    }

    @Override
    public Integer getUnreadCount(Integer userId) {
        LambdaQueryWrapper<PrivateMessage> qw = new LambdaQueryWrapper<PrivateMessage>()
                .eq(PrivateMessage::getToUserId, userId)
                .eq(PrivateMessage::getIsRead, 0)
                .eq(PrivateMessage::getIsRevoked, 0) // 过滤掉已撤回的消息
                .and(w -> w.eq(PrivateMessage::getExamineStatus, ExamineStatusEnum.PASS.getCode())
                        .or()
                        .isNull(PrivateMessage::getExamineStatus));

        return Math.toIntExact(privateMessageMapper.selectCount(qw));
    }
}
