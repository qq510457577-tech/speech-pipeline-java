package com.speechpipeline.config;

import com.speechpipeline.websocket.AsrWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AsrWebSocketHandler asrWebSocketHandler;
    private final NlsClientManager nlsClientManager;
    private final AliConfig aliConfig;

    public WebSocketConfig(AsrWebSocketHandler asrWebSocketHandler, 
                           NlsClientManager nlsClientManager, 
                           AliConfig aliConfig) {
        this.asrWebSocketHandler = asrWebSocketHandler;
        this.nlsClientManager = nlsClientManager;
        this.aliConfig = aliConfig;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(asrWebSocketHandler, "/ws/asr", "/speech/ws/asr")
                .setAllowedOrigins("*");
    }
}
