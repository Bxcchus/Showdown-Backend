package lol.pinkward.showdown.match;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
class MatchWebSocketConfiguration implements WebSocketConfigurer {
    private final MatchRealtimeHub hub;
    private final String[] webOrigins;

    MatchWebSocketConfiguration(
            MatchRealtimeHub hub,
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
        registry.addHandler(hub, "/api/v2/realtime/matches")
                .addInterceptors(hub)
                .setAllowedOrigins(webOrigins);
    }
}
