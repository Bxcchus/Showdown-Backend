package lol.pinkward.showdown.player;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.SubProtocolCapable;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import tools.jackson.databind.ObjectMapper;

@Component
class PartyRealtimeHub extends TextWebSocketHandler implements HandshakeInterceptor, SubProtocolCapable {

    private static final Logger LOGGER = LoggerFactory.getLogger(PartyRealtimeHub.class);
    private static final String PLAYER_ID = "playerId";
    private final JwtDecoder jwtDecoder;
    private final ObjectMapper objectMapper;
    private final Map<UUID, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    PartyRealtimeHub(JwtDecoder jwtDecoder, ObjectMapper objectMapper) {
        this.jwtDecoder = jwtDecoder;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            Map<String, Object> attributes) {
        try {
            Jwt jwt = jwtDecoder.decode(bearerToken(request));
            if (!hasScope(jwt, "party:manage")) throw new IllegalArgumentException("Missing party scope");
            attributes.put(PLAYER_ID, UUID.fromString(jwt.getSubject()));
            return true;
        } catch (RuntimeException exception) {
            LOGGER.warn("Party WebSocket handshake rejected: {}", exception.getMessage());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler handler, Exception exception) {}

    @Override
    public List<String> getSubProtocols() {
        return List.of("showdown-v1");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        UUID playerId = (UUID) raw.getAttributes().get(PLAYER_ID);
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(raw, 5_000, 64 * 1024);
        sessions.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
        send(session, new RealtimeEvent("CONNECTED", Instant.now()));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID playerId = (UUID) session.getAttributes().get(PLAYER_ID);
        if (playerId == null) return;
        Set<WebSocketSession> playerSessions = sessions.get(playerId);
        if (playerSessions != null) {
            playerSessions.removeIf(candidate -> candidate.getId().equals(session.getId()) || !candidate.isOpen());
            if (playerSessions.isEmpty()) sessions.remove(playerId);
        }
    }

    void publishAfterCommit(Set<UUID> playerIds, String type) {
        Runnable publish = () -> playerIds.forEach(playerId -> publish(playerId, type));
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish.run(); }
            });
        } else {
            publish.run();
        }
    }

    private void publish(UUID playerId, String type) {
        Set<WebSocketSession> playerSessions = sessions.get(playerId);
        if (playerSessions == null) return;
        playerSessions.removeIf(session -> !session.isOpen() || !send(session, new RealtimeEvent(type, Instant.now())));
    }

    private boolean send(WebSocketSession session, RealtimeEvent event) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
            return true;
        } catch (IOException | RuntimeException exception) {
            try { session.close(CloseStatus.SERVER_ERROR); } catch (IOException ignored) {}
            return false;
        }
    }

    private static String bearerProtocol(List<String> headers) {
        return headers.stream().flatMap(value -> List.of(value.split(",")).stream())
                .map(String::trim)
                .filter(value -> value.startsWith("bearer."))
                .map(value -> value.substring("bearer.".length()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Missing bearer protocol"));
    }

    private static String bearerToken(ServerHttpRequest request) {
        String authorization = request.getHeaders().getFirst("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length());
        }
        return bearerProtocol(request.getHeaders().get("Sec-WebSocket-Protocol"));
    }

    private static boolean hasScope(Jwt jwt, String required) {
        Object scope = jwt.getClaims().get("scope");
        if (scope instanceof Collection<?> scopes) {
            return scopes.stream().map(String::valueOf).anyMatch(required::equals);
        }
        return scope != null && List.of(String.valueOf(scope).split(" ")).contains(required);
    }

    private record RealtimeEvent(String type, Instant occurredAt) {}
}
