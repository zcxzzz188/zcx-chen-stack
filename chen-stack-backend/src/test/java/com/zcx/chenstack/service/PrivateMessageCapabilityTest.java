package com.zcx.chenstack.service;

import com.zcx.chenstack.domain.entity.PrivateMessage;
import com.zcx.chenstack.exception.BlogException;
import com.zcx.chenstack.mapper.PrivateMessageMapper;
import com.zcx.chenstack.service.impl.PrivateMessageServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PrivateMessageCapabilityTest {
    @Test void revocationRequiresSenderOwnershipAndTwoMinuteWindow() {
        PrivateMessageMapper mapper = mock(PrivateMessageMapper.class);
        PrivateMessageServiceImpl service = new PrivateMessageServiceImpl();
        ReflectionTestUtils.setField(service, "privateMessageMapper", mapper);
        PrivateMessage message = new PrivateMessage();
        message.setId(42); message.setFromUserId(7); message.setToUserId(8); message.setIsRevoked(0);
        message.setCreateTime(new Date(System.currentTimeMillis() - 10_000));
        when(mapper.selectById(42)).thenReturn(message);
        assertThrows(BlogException.class, () -> service.revokeMessage(42, 8));
        verify(mapper, never()).updateById(any(PrivateMessage.class));
        message.setCreateTime(new Date(System.currentTimeMillis() - 121_000));
        assertThrows(BlogException.class, () -> service.revokeMessage(42, 7));
        verify(mapper, never()).updateById(any(PrivateMessage.class));
        message.setCreateTime(new Date());
        assertSame(message, service.revokeMessage(42, 7));
        assertEquals(1, message.getIsRevoked());
        verify(mapper).updateById(message);
        assertThrows(BlogException.class, () -> service.revokeMessage(42, 7));
        verify(mapper, times(1)).updateById(any(PrivateMessage.class));
    }
}
