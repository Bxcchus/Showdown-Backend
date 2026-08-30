package lol.pinkward.showdown.gateway;

import java.util.UUID;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
public final class CorrelationIdFilter implements GlobalFilter, Ordered {

    static final String HEADER = "X-Correlation-ID";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = normalized(exchange.getRequest().getHeaders().getFirst(HEADER));
        ServerWebExchange updated = exchange.mutate()
                .request(request -> request.headers(headers -> headers.set(HEADER, correlationId)))
                .build();
        updated.getResponse().getHeaders().set(HEADER, correlationId);
        return chain.filter(updated);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    private static String normalized(String candidate) {
        if (candidate != null) {
            try {
                return UUID.fromString(candidate).toString();
            } catch (IllegalArgumentException ignored) {
                // Untrusted external values are replaced instead of propagated.
            }
        }
        return UUID.randomUUID().toString();
    }
}
