package lol.pinkward.showdown.player;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
class PartyWebSocketConfiguration implements WebSocketConfigurer {

    private final PartyRealtimeHub hub;
    private final String[] webOrigins;

    PartyWebSocketConfiguration(
            PartyRealtimeHub hub,
            @Value("${pinkward.web-origins:http://localhost:3000}") String webOrigins) {
        this.hub = hub;
        this.webOrigins = Arrays.stream(webOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isBlank())
                .distinct()
                .toArray(String[]::new);
        if (this.webOrigins.length == 0) {
            throw new IllegalArgumentException("At least one exact WebSocket origin is required");
        }
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(hub, "/api/v2/realtime/party")
                .addInterceptors(hub)
                .setAllowedOrigins(webOrigins);
    }
}
