package lol.pinkward.showdown.gateway;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.http.HttpStatusCode;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR;

@Component
class GatewayTrafficMetricsFilter implements GlobalFilter, Ordered {

    private final MeterRegistry meters;

    GatewayTrafficMetricsFilter(MeterRegistry meters) {
        this.meters = meters;
    }

    @Override
    public int getOrder() {
        return -100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        long started = System.nanoTime();
        return chain.filter(exchange).doFinally(signal -> {
            Route route = exchange.getAttribute(GATEWAY_ROUTE_ATTR);
            String routeId = route == null ? "unmatched" : route.getId();
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            String statusTag = status == null ? "unknown" : Integer.toString(status.value());
            meters.counter("showdown.gateway.requests", "route", routeId, "status", statusTag).increment();
            if (status != null && status.value() == 429) {
                meters.counter("showdown.gateway.rate.limit.rejections", "route", routeId).increment();
            }
            if (status != null && status.is5xxServerError()) {
                meters.counter("showdown.gateway.server.errors", "route", routeId).increment();
            }
            Timer.builder("showdown.gateway.request.duration")
                    .tag("route", routeId)
                    .register(meters)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        });
    }
}
