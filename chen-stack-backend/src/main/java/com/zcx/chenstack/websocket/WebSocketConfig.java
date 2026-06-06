package com.zcx.chenstack.websocket;

import com.zcx.chenstack.config.ChenStackConfig;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置类
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Resource
    private PrivateMessageWebSocketHandler privateMessageWebSocketHandler;

    @Resource
    private WebSocketHandshakeInterceptor handshakeInterceptor;

    @Resource
    private ChenStackConfig chenStackConfig;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(privateMessageWebSocketHandler, "/ws/message")
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins(chenStackConfig.getAllowOrigins().toArray(new String[0]));
    }
}
