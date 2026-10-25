package com.zcx.chenstack.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zcx.chenstack.domain.entity.Conversation;
import com.zcx.chenstack.domain.entity.PrivateMessage;
import com.zcx.chenstack.domain.vo.ConversationVo;
import com.zcx.chenstack.mapper.ConversationMapper;
import com.zcx.chenstack.mapper.PrivateMessageMapper;
import com.zcx.chenstack.mapper.SysUserMapper;
import com.zcx.chenstack.redis.RedisComponent;
import com.zcx.chenstack.service.impl.ConversationServiceImpl;
import com.zcx.chenstack.service.impl.PrivateMessageServiceImpl;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConversationUnreadTest {
    private final ConversationMapper conversations = mock(ConversationMapper.class);
    private final PrivateMessageMapper messages = mock(PrivateMessageMapper.class);
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final RedisComponent redis = mock(RedisComponent.class);
    private final ConversationServiceImpl service = new ConversationServiceImpl();

    @BeforeAll
    static void initializeMappings() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> type : List.of(Conversation.class, PrivateMessage.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(configuration, "conversation-unread-test"), type);
        }
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "conversationMapper", conversations);
        ReflectionTestUtils.setField(service, "privateMessageMapper", messages);
        ReflectionTestUtils.setField(service, "sysUserMapper", users);
        ReflectionTestUtils.setField(service, "redisComponent", redis);
    }

    @Test
    void conversationSnapshotUsesGroupedUnreadMessagesInsteadOfStaleCounter() {
        Conversation conversation = new Conversation();
        conversation.setUserId(8); conversation.setTargetUserId(7); conversation.setUnreadCount(999);
        when(conversations.selectList(any(Wrapper.class))).thenReturn(List.of(conversation));
        when(messages.selectMaps(any(Wrapper.class)))
                .thenReturn(List.of(Map.of("from_user_id", 7, "unread_count", 2L)));
        when(users.selectBatchIds(anyCollection())).thenReturn(List.of());
        when(redis.batchGetUserOnlineStatus(anyList())).thenReturn(Map.of());
        List<ConversationVo> result = service.getConversationList(8);
        assertEquals(2, result.getFirst().getUnreadCount());
        verify(messages, times(1)).selectMaps(any(Wrapper.class));
        verify(messages, never()).selectCount(any(Wrapper.class));
    }

    @Test
    void arrivalAfterReadUpdateSurvivesLegacyConversationCounterClear() {
        AtomicLong unread = new AtomicLong(3);
        when(messages.update(isNull(), any(Wrapper.class))).thenAnswer(invocation -> {
            unread.set(0);
            return 3;
        });
        when(conversations.update(isNull(), any(Wrapper.class))).thenAnswer(invocation -> {
            unread.incrementAndGet();
            return 1;
        });
        when(messages.selectCount(any(Wrapper.class))).thenAnswer(invocation -> unread.get());
        service.clearUnreadCount(8, 7);
        assertEquals(1, service.getUnreadCount(8, 7));
        ArgumentCaptor<LambdaUpdateWrapper<PrivateMessage>> read = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(messages).update(isNull(), read.capture());
        String where = read.getValue().getSqlSegment();
        assertTrue(where.contains("from_user_id ="));
        assertTrue(where.contains("to_user_id ="));
        assertTrue(where.contains("is_read ="));
        assertTrue(where.contains("is_revoked ="));
        assertTrue(where.contains("examine_status IS NULL"));
        assertTrue(read.getValue().getSqlSet().contains("read_time="));
        assertNotNull(TableInfoHelper.getTableInfo(PrivateMessage.class).getLogicDeleteFieldInfo());
    }

    @Test
    void unreadTotalUsesMessageRowsAndLegacyReadEntrypointDelegatesToSharedOperation() {
        PrivateMessageServiceImpl privateMessages = new PrivateMessageServiceImpl();
        ConversationService sharedRead = mock(ConversationService.class);
        ReflectionTestUtils.setField(privateMessages, "privateMessageMapper", messages);
        ReflectionTestUtils.setField(privateMessages, "conversationService", sharedRead);
        when(messages.selectCount(any(Wrapper.class))).thenReturn(4L);
        assertEquals(4, privateMessages.getUnreadCount(8));
        privateMessages.markAsRead(7, 8);
        verify(sharedRead).clearUnreadCount(8, 7);
    }
}
