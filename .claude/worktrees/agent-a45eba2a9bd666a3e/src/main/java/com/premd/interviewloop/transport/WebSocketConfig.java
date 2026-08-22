package com.premd.interviewloop.transport;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Raw WebSocket configuration (not STOMP).
 * One client, one message shape — STOMP's broker semantics buy nothing here.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final InterviewWebSocketHandler interviewHandler;

    public WebSocketConfig(InterviewWebSocketHandler interviewHandler) {
        this.interviewHandler = interviewHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(interviewHandler, "/ws/interview")
                .setAllowedOrigins("*");  // Single-user local app — no CORS restriction
    }
}
