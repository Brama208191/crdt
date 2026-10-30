package dev.crdt.editor;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocketMessageBroker
class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final int MAX_MESSAGE_BYTES = 1024 * 1024;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns("*");
        // Handle each session's frames in order, so a client's SUBSCRIBE is registered before its
        // later SENDs (e.g. join) trigger broadcasts it needs to receive.
        registry.setPreserveReceiveOrder(true);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.setApplicationDestinationPrefixes("/app");
        registry.enableSimpleBroker("/topic");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        // Large pastes arrive as op batches of a few hundred KB.
        registration.setMessageSizeLimit(MAX_MESSAGE_BYTES);
        registration.setSendBufferSizeLimit(8 * MAX_MESSAGE_BYTES);
    }

    /**
     * Tomcat's own WebSocket buffer defaults to 8 KB and closes the connection on anything larger,
     * regardless of the STOMP limit above, so it must be raised too.
     */
    @Bean
    ServletServerContainerFactoryBean webSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(MAX_MESSAGE_BYTES);
        return container;
    }
}
