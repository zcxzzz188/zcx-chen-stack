package com.zcx.chenstack.websocket;

import com.zcx.chenstack.redis.RedisComponent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketSession;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketSessionManagerTest {
    @Test
    void managerQueueAndHandlerWritesShareOneSessionLock() throws Exception {
        WebSocketSessionManager manager = new WebSocketSessionManager();
        ReflectionTestUtils.setField(manager, "redisComponent", mock(RedisComponent.class));
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        manager.registerSession(7, session);
        CountDownLatch firstWrite = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        doAnswer(invocation -> {
            if (writes.incrementAndGet() == 1) {
                firstWrite.countDown();
                assertTrue(release.await(3, TimeUnit.SECONDS));
            }
            return null;
        }).when(session).sendMessage(any(org.springframework.web.socket.WebSocketMessage.class));
        ExecutorService workers = Executors.newFixedThreadPool(3);
        try {
            Future<?> first = workers.submit(() -> manager.sendMessage(7, "test first"));
            assertTrue(firstWrite.await(1, TimeUnit.SECONDS));
            Future<Boolean> queued = workers.submit(() -> manager.sendToWebSocketDirect(7, "test queued"));
            Future<?> reply = workers.submit(() -> ReflectionTestUtils.invokeMethod(
                    new PrivateMessageWebSocketHandler(), "sendMessage", session,
                    com.zcx.chenstack.domain.dto.WebSocketMessage.heartbeat()));
            assertThrows(TimeoutException.class, () -> queued.get(100, TimeUnit.MILLISECONDS));
            assertThrows(TimeoutException.class, () -> reply.get(100, TimeUnit.MILLISECONDS));
            assertEquals(1, writes.get());
            release.countDown();
            first.get(1, TimeUnit.SECONDS);
            assertTrue(queued.get(1, TimeUnit.SECONDS));
            reply.get(1, TimeUnit.SECONDS);
            assertEquals(3, writes.get());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void lateCloseOfPreviousConnectionCannotRemoveReplacement() {
        RedisComponent redis = mock(RedisComponent.class);
        WebSocketSessionManager manager = new WebSocketSessionManager();
        ReflectionTestUtils.setField(manager, "redisComponent", redis);
        WebSocketSession old = mock(WebSocketSession.class);
        WebSocketSession replacement = mock(WebSocketSession.class);
        manager.registerSession(7, old);
        manager.registerSession(7, replacement);
        assertFalse(manager.removeSession(7, old));
        assertSame(replacement, manager.getSession(7));
        verify(redis, never()).removeUserOnlineStatus(7);
        assertTrue(manager.removeSession(7, replacement));
        assertNull(manager.getSession(7));
        verify(redis).removeUserOnlineStatus(7);
    }
}
