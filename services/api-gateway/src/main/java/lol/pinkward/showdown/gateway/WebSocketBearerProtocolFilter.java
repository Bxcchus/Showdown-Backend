package lol.pinkward.showdown.gateway;

import java.util.List;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class WebSocketBearerProtocolFilter implements WebFilter {
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/api/v2/realtime/")
                || exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION) != null) {
            return chain.filter(exchange);
        }
        String token = exchange.getRequest().getHeaders().getOrDefault("Sec-WebSocket-Protocol", List.of())
                .stream().flatMap(value -> List.of(value.split(",")).stream())
                .map(String::trim).filter(value -> value.startsWith("bearer."))
                .map(value -> value.substring("bearer.".length())).findFirst().orElse(null);
        if (token == null) return chain.filter(exchange);
        ServerWebExchange authenticated = exchange.mutate().request(request ->
                request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).build();
        return chain.filter(authenticated);
    }
}
