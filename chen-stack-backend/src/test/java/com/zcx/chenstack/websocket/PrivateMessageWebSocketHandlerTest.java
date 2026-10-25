package com.zcx.chenstack.websocket;

import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.zcx.chenstack.domain.dto.WebSocketMessage;
import com.zcx.chenstack.domain.entity.Photo;
import com.zcx.chenstack.domain.entity.PrivateMessage;
import com.zcx.chenstack.domain.enums.ExamineStatusEnum;
import com.zcx.chenstack.domain.result.AuditResult;
import com.zcx.chenstack.mapper.PhotoMapper;
import com.zcx.chenstack.service.PrivateMessageService;
import com.zcx.chenstack.utils.TextAuditUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PrivateMessageWebSocketHandlerTest {
    private final PrivateMessageService service = mock(PrivateMessageService.class);
    private final PhotoMapper photos = mock(PhotoMapper.class);
    private final TextAuditUtils textAudit = mock(TextAuditUtils.class);
    private final PrivateMessageWebSocketHandler handler = new PrivateMessageWebSocketHandler();
    private final WebSocketSession sender = mock(WebSocketSession.class);

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(handler, "privateMessageService", service);
        ReflectionTestUtils.setField(handler, "photoMapper", photos);
        ReflectionTestUtils.setField(handler, "textAuditUtils", textAudit);
        when(sender.getAttributes()).thenReturn(Map.of("userId", 7));
        when(sender.isOpen()).thenReturn(true);
        when(service.save(any(PrivateMessage.class))).thenAnswer(invocation -> {
            PrivateMessage message = invocation.getArgument(0);
            message.setId(42).setCreateTime(new Date(1_700_000_000_000L));
            return true;
        });
    }

    @Test
    void imagePersistsBeforeCompleteReceiptAndRechecksAlreadyCompletedAudit() throws Exception {
        Photo approved = new Photo(); approved.setExamineStatus(ExamineStatusEnum.PASS.getCode());
        when(photos.selectOne(any(Wrapper.class))).thenReturn(approved);
        String imageUrl = "https://example.invalid/message/test.png";
        handler.handleTextMessage(sender, request(2, "", imageUrl));
        InOrder order = inOrder(service, sender, photos);
        order.verify(service).save(argThat(message -> message.getFromUserId() == 7
                && message.getToUserId() == 8 && message.getExamineStatus().equals(ExamineStatusEnum.WAIT.getCode())));
        ArgumentCaptor<TextMessage> ack = ArgumentCaptor.forClass(TextMessage.class);
        order.verify(sender).sendMessage(ack.capture());
        order.verify(photos).selectOne(any(Wrapper.class));
        order.verify(service).syncImageAuditStatus(List.of(imageUrl), ExamineStatusEnum.PASS.getCode());
        WebSocketMessage event = JSON.parseObject(ack.getValue().getPayload(), WebSocketMessage.class);
        assertEquals("SEND_SUCCESS", event.getType());
        assertEquals(42, event.getMessageId());
        assertEquals(7, event.getFromUserId());
        assertEquals(8, event.getToUserId());
        assertEquals(2, event.getMessageType());
        assertEquals(imageUrl, event.getImageUrl());
        assertEquals(new Date(1_700_000_000_000L), event.getCreateTime());
        assertEquals(ExamineStatusEnum.WAIT.getCode(), event.getCode());
        verifyNoInteractions(textAudit);
        verify(service, never()).deliverApprovedMessage(any());
    }

    @Test
    void textUsesVerifiedSenderAndSameApprovedDelivery() {
        when(textAudit.auditTextWithDetailsSplit(anyString()))
                .thenReturn(new AuditResult(ExamineStatusEnum.PASS.getCode(), "测试通过"));
        handler.handleTextMessage(sender, request(1, "测试文字", null));
        verify(service).save(argThat(message -> message.getFromUserId() == 7 && message.getToUserId() == 8));
        verify(service).deliverApprovedMessage(argThat(message -> message.getId() == 42
                && message.getExamineStatus().equals(ExamineStatusEnum.PASS.getCode())));
        verify(service, never()).syncImageAuditStatus(anyList(), anyInt());
        verifyNoInteractions(photos);
    }

    @Test
    void failedPersistenceNeverAcknowledgesSuccessOrPublishes() throws Exception {
        when(service.save(any(PrivateMessage.class))).thenReturn(false);
        handler.handleTextMessage(sender, request(2, "", "https://example.invalid/message/test.png"));
        verify(service, never()).deliverApprovedMessage(any());
        verify(service, never()).syncImageAuditStatus(anyList(), anyInt());
        verifyNoInteractions(photos);
        ArgumentCaptor<TextMessage> reply = ArgumentCaptor.forClass(TextMessage.class);
        verify(sender).sendMessage(reply.capture());
        assertEquals("SYSTEM", JSON.parseObject(reply.getValue().getPayload(), WebSocketMessage.class).getType());
    }

    private static TextMessage request(int type, String content, String imageUrl) {
        return new TextMessage(JSON.toJSONString(WebSocketMessage.builder().type("SEND_MESSAGE")
                .fromUserId(99).toUserId(8).messageType(type).content(content).imageUrl(imageUrl).build()));
    }
}
