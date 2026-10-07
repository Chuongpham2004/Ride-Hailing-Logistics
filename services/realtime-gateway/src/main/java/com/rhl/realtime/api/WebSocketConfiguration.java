package com.rhl.realtime.api;

import com.rhl.realtime.RealtimeProperties;
import com.rhl.realtime.infrastructure.security.SecurityConfiguration;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/** Plain WebSocket with JSON messages (README §8.4), no sub-protocol: native apps and browsers alike. */
@Configuration(proxyBeanMethods = false)
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfiguration implements WebSocketConfigurer {

    private final RealtimeWebSocketHandler handler;
    private final RealtimeProperties properties;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, SecurityConfiguration.WS_PATH)
                .setAllowedOriginPatterns(properties.realtime().allowedOrigins().toArray(String[]::new));
    }

    /** Oversized client messages close the connection (1009) instead of being buffered. */
    @Bean
    public ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(properties.realtime().maxMessageBytes());
        container.setMaxBinaryMessageBufferSize(1024);
        return container;
    }
}
