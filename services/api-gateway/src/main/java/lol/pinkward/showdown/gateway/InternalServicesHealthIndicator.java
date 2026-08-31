package lol.pinkward.showdown.gateway;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component("internalServices")
final class InternalServicesHealthIndicator implements ReactiveHealthIndicator {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(2);

    private final WebClient webClient;
    private final Map<String, String> targets;

    @Autowired
    InternalServicesHealthIndicator(
            @Value("${pinkward.health.identity-uri}") String identityUri,
            @Value("${pinkward.health.matchmaking-uri}") String matchmakingUri,
            @Value("${pinkward.health.match-uri}") String matchUri,
            @Value("${pinkward.health.player-uri}") String playerUri) {
        this(WebClient.create(), Map.of(
                "identity", identityUri,
                "matchmaking", matchmakingUri,
                "match", matchUri,
                "player", playerUri));
    }

    InternalServicesHealthIndicator(WebClient webClient, Map<String, String> targets) {
        this.webClient = webClient;
        this.targets = Map.copyOf(targets);
    }

    @Override
    public Mono<Health> health() {
        return Flux.fromIterable(targets.entrySet())
                .flatMapSequential(target -> probe(target.getValue())
                        .map(up -> Map.entry(target.getKey(), up ? "UP" : "DOWN")))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new)
                .map(statuses -> statuses.values().stream().allMatch("UP"::equals)
                        ? Health.up().withDetails(statuses).build()
                        : Health.down().withDetails(statuses).build());
    }

    private Mono<Boolean> probe(String uri) {
        return webClient.get()
                .uri(uri)
                .exchangeToMono(response -> Mono.just(response.statusCode().is2xxSuccessful()))
                .timeout(PROBE_TIMEOUT)
                .onErrorReturn(false);
    }
}
