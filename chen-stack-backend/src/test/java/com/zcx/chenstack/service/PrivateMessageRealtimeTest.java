package com.zcx.chenstack.service;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zcx.chenstack.domain.dto.WebSocketMessage;
import com.zcx.chenstack.domain.entity.PrivateMessage;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.domain.enums.ExamineStatusEnum;
import com.zcx.chenstack.mapper.PrivateMessageMapper;
import com.zcx.chenstack.mapper.SysUserMapper;
import com.zcx.chenstack.service.impl.PrivateMessageServiceImpl;
import com.zcx.chenstack.utils.EmailUtils;
import com.zcx.chenstack.websocket.WebSocketSessionManager;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.WebSocketSession;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PrivateMessageRealtimeTest {
    private final PrivateMessageMapper mapper = mock(PrivateMessageMapper.class);
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final ConversationService conversations = mock(ConversationService.class);
    private final WebSocketSessionManager sessions = mock(WebSocketSessionManager.class);
    private final UserSettingsService settings = mock(UserSettingsService.class);
    private final EmailUtils email = mock(EmailUtils.class);
    private final PrivateMessageServiceImpl service = new PrivateMessageServiceImpl();

    @BeforeAll
    static void initializeMapping() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "private-message-test"),
                PrivateMessage.class);
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "privateMessageMapper", mapper);
        ReflectionTestUtils.setField(service, "sysUserMapper", users);
        ReflectionTestUtils.setField(service, "conversationService", conversations);
        ReflectionTestUtils.setField(service, "sessionManager", sessions);
        ReflectionTestUtils.setField(service, "userSettingsService", settings);
        ReflectionTestUtils.setField(service, "emailUtils", email);
        SysUser sender = new SysUser();
        sender.setId(7); sender.setNickname("测试发送者");
        SysUser recipient = new SysUser();
        recipient.setId(8); recipient.setEmail("recipient@example.invalid");
        when(users.selectById(7)).thenReturn(sender);
        when(users.selectById(8)).thenReturn(recipient);
        when(conversations.getUnreadCount(8, 7)).thenReturn(1);
        when(settings.getReceivePrivateMessageEmail(8)).thenReturn(1);
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(sessions.getSession(anyInt())).thenReturn(session);
    }

    @Test
    void imageApprovalAndRepeatedCallbackDeliverOneMessageAndOneEmail() {
        PrivateMessage image = image();
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(image));
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1, 0);
        service.syncImageAuditStatus(List.of(image.getImageUrl()), ExamineStatusEnum.PASS.getCode());
        service.syncImageAuditStatus(List.of(image.getImageUrl()), ExamineStatusEnum.PASS.getCode());
        verify(conversations, times(1)).updateConversation(7, 8, image);
        ArgumentCaptor<String> push = ArgumentCaptor.forClass(String.class);
        verify(sessions, times(1)).sendMessage(eq(8), push.capture());
        WebSocketMessage event = JSON.parseObject(push.getValue(), WebSocketMessage.class);
        assertEquals("NEW_MESSAGE", event.getType());
        assertEquals(42, event.getMessageId());
        assertEquals(2, event.getMessageType());
        assertEquals(image.getImageUrl(), event.getImageUrl());
        assertEquals(1, event.getUnreadCount());
        verify(sessions, times(1)).sendMessage(eq(7), anyString());
        ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
        verify(email, times(1)).sendHtmlMail(eq("recipient@example.invalid"), anyString(),
                eq("private-message-notification"), data.capture());
        assertEquals("[图片]", data.getValue().get("messagePreview"));
    }

    @Test
    void approvalCompareAndSetCannotSelectRejectedRevokedOrDeletedMessages() {
        PrivateMessage image = image();
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(image));
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(0);
        service.syncImageAuditStatus(List.of(image.getImageUrl()), ExamineStatusEnum.PASS.getCode());
        ArgumentCaptor<LambdaUpdateWrapper<PrivateMessage>> update = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(mapper).update(isNull(), update.capture());
        String where = update.getValue().getSqlSegment();
        assertTrue(where.contains("is_revoked ="));
        assertTrue(where.contains("examine_status ="));
        assertTrue(where.contains("examine_status IS NULL"));
        assertTrue(update.getValue().getParamNameValuePairs().containsValue(ExamineStatusEnum.WAIT.getCode()));
        assertFalse(update.getValue().getParamNameValuePairs().containsValue(ExamineStatusEnum.NO_PASS.getCode()));
        assertNotNull(TableInfoHelper.getTableInfo(PrivateMessage.class).getLogicDeleteFieldInfo());
        verifyNoInteractions(conversations, email);
        verify(sessions, never()).sendMessage(anyInt(), anyString());
    }

    @Test
    void waitingAndRejectedImagesNeverReachRecipientOrEmail() {
        PrivateMessage image = image();
        service.syncImageAuditStatus(List.of(image.getImageUrl()), ExamineStatusEnum.WAIT.getCode());
        verifyNoInteractions(mapper);
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(image));
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        service.syncImageAuditStatus(List.of(image.getImageUrl()), ExamineStatusEnum.NO_PASS.getCode());
        verify(sessions).sendMessage(eq(7), anyString());
        verify(sessions, never()).sendMessage(eq(8), anyString());
        verifyNoInteractions(conversations, email);
    }

    @Test
    void pushAndEmailWaitUntilCommitAndRollbackDoesNotPublish() {
        PrivateMessage image = image();
        when(mapper.selectList(any(Wrapper.class))).thenReturn(List.of(image));
        when(mapper.update(isNull(), any(Wrapper.class))).thenReturn(1);
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.syncImageAuditStatus(List.of(image.getImageUrl()), ExamineStatusEnum.PASS.getCode());
            verify(conversations).updateConversation(7, 8, image);
            verify(sessions, never()).sendMessage(anyInt(), anyString());
            verifyNoInteractions(email);
            List<TransactionSynchronization> callbacks = TransactionSynchronizationManager.getSynchronizations();
            callbacks.forEach(TransactionSynchronization::afterCommit);
            verify(sessions).sendMessage(eq(8), anyString());
            verify(email).sendHtmlMail(anyString(), anyString(), anyString(), anyMap());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        clearInvocations(sessions, email);
        image.setExamineStatus(ExamineStatusEnum.WAIT.getCode());
        TransactionSynchronizationManager.initSynchronization();
        try {
            service.syncImageAuditStatus(List.of(image.getImageUrl()), ExamineStatusEnum.PASS.getCode());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(callback -> callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(sessions, never()).sendMessage(anyInt(), anyString());
            verifyNoInteractions(email);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void textUsesSameDeliveryAndDisabledOrNonFirstUnreadSuppressesEmail() {
        PrivateMessage text = image().setMessageType(1).setContent("测试文字")
                .setExamineStatus(ExamineStatusEnum.PASS.getCode());
        service.deliverApprovedMessage(text);
        verify(email).sendHtmlMail(anyString(), anyString(), anyString(), anyMap());
        clearInvocations(email);
        when(settings.getReceivePrivateMessageEmail(8)).thenReturn(0);
        service.deliverApprovedMessage(text);
        verifyNoInteractions(email);
        when(settings.getReceivePrivateMessageEmail(8)).thenReturn(1);
        when(conversations.getUnreadCount(8, 7)).thenReturn(2);
        service.deliverApprovedMessage(text);
        verifyNoInteractions(email);
        verify(sessions, times(3)).sendMessage(eq(8), anyString());
    }

    @Test
    void missingMailboxAndMailFailureDoNotPreventRealtimeDelivery() {
        PrivateMessage image = image().setExamineStatus(ExamineStatusEnum.PASS.getCode());
        when(users.selectById(8)).thenReturn(new SysUser());
        service.deliverApprovedMessage(image);
        verifyNoInteractions(email);
        SysUser recipient = new SysUser(); recipient.setEmail("recipient@example.invalid");
        when(users.selectById(8)).thenReturn(recipient);
        doThrow(new IllegalStateException("test mail unavailable")).when(email)
                .sendHtmlMail(anyString(), anyString(), anyString(), anyMap());
        assertDoesNotThrow(() -> service.deliverApprovedMessage(image));
        verify(sessions, times(2)).sendMessage(eq(8), anyString());
    }

    private static PrivateMessage image() {
        return new PrivateMessage().setId(42).setFromUserId(7).setToUserId(8).setMessageType(2)
                .setImageUrl("https://example.invalid/message/test.png").setContent("")
                .setExamineStatus(ExamineStatusEnum.WAIT.getCode()).setIsRevoked(0).setIsDeleted(0)
                .setIsRead(0).setCreateTime(new Date());
    }
}
