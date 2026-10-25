package com.zcx.chenstack.websocket;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.signers.JWTSignerUtil;
import com.zcx.chenstack.domain.entity.SysUser;
import com.zcx.chenstack.domain.enums.StatusEnum;
import com.zcx.chenstack.mapper.SysUserMapper;
import com.zcx.chenstack.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSocketHandshakeInterceptorTest {
    private static final String TEST_SECRET = "g2-handshake-test-only-secret";
    private final JwtUtils jwt = new JwtUtils();
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final WebSocketHandshakeInterceptor interceptor = new WebSocketHandshakeInterceptor();
    private final Map<String, Object> attributes = new HashMap<>();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(jwt, "secret", TEST_SECRET);
        ReflectionTestUtils.setField(interceptor, "jwtUtils", jwt);
        ReflectionTestUtils.setField(interceptor, "sysUserMapper", users);
    }

    @Test
    void acceptsNormalUserAndUsesVerifiedIdentity() {
        when(users.selectById(7)).thenReturn(user(StatusEnum.NORMAL));
        assertTrue(handshake("?token=" + jwt.createToken(7) + "&userId=99"));
        assertEquals(7, attributes.get("userId"));
        verify(users).selectById(7);
    }

    @Test
    void rejectsDisabledUserWithOtherwiseValidToken() {
        when(users.selectById(7)).thenReturn(user(StatusEnum.DISABLE));
        assertFalse(handshake("?token=" + jwt.createToken(7)));
        assertTrue(attributes.isEmpty());
    }

    @Test
    void rejectsMissingOrLogicallyDeletedUser() {
        // SysUser 的 @TableLogic 使已删除账号与不存在账号均不被 selectById 返回。
        when(users.selectById(7)).thenReturn(null);
        assertFalse(handshake("?token=" + jwt.createToken(7)));
        assertTrue(attributes.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?userId=7", "?token=", "?token=not-a-jwt"})
    void rejectsMissingOrMalformedToken(String query) {
        assertFalse(handshake(query));
        assertTrue(attributes.isEmpty());
        verifyNoInteractions(users);
    }

    @Test
    void rejectsInvalidSignature() {
        JwtUtils otherSigner = new JwtUtils();
        ReflectionTestUtils.setField(otherSigner, "secret", "different-test-only-secret");
        assertFalse(handshake("?token=" + otherSigner.createToken(7)));
        assertTrue(attributes.isEmpty());
        verifyNoInteractions(users);
    }

    @Test
    void rejectsExpiredToken() {
        String expired = JWT.create()
                .setExpiresAt(new Date(System.currentTimeMillis() - 60_000))
                .addPayloads(Map.of("id", 7))
                .setSigner(JWTSignerUtil.createSigner("HS256", TEST_SECRET.getBytes(StandardCharsets.UTF_8)))
                .sign();
        assertFalse(handshake("?token=" + expired));
        assertTrue(attributes.isEmpty());
        verifyNoInteractions(users);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -7})
    void rejectsNonPositiveIdentity(int id) {
        assertFalse(handshake("?token=" + jwt.createToken(id)));
        assertTrue(attributes.isEmpty());
        verifyNoInteractions(users);
    }

    @Test
    void rejectsDatabaseFailureWithoutAcceptingIdentity() {
        when(users.selectById(7)).thenThrow(new IllegalStateException("test database unavailable"));
        assertFalse(handshake("?token=" + jwt.createToken(7)));
        assertTrue(attributes.isEmpty());
    }

    private boolean handshake(String query) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("http://localhost/ws/message" + query));
        return interceptor.beforeHandshake(request, null, null, attributes);
    }

    private static SysUser user(StatusEnum status) {
        SysUser user = new SysUser();
        user.setId(7);
        user.setStatus(status.getStatus());
        user.setIsDeleted(0);
        return user;
    }
}
