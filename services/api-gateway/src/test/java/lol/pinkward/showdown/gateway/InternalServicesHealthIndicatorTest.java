package lol.pinkward.showdown.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

class InternalServicesHealthIndicatorTest {

    @Test
    void reportsUpOnlyWhenEveryInternalServiceIsReady() {
        DisposableServer server = readinessServer();
        try {
            String readyUri = "http://127.0.0.1:" + server.port() + "/ready";
            InternalServicesHealthIndicator indicator = new InternalServicesHealthIndicator(
                    WebClient.create(),
                    Map.of("identity", readyUri, "match", readyUri));

            Health health = indicator.health().block(Duration.ofSeconds(5));

            assertThat(health).isNotNull();
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails()).containsEntry("identity", "UP").containsEntry("match", "UP");
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void reportsDownWhenOneInternalServiceIsNotReady() {
        DisposableServer server = readinessServer();
        try {
            String baseUri = "http://127.0.0.1:" + server.port();
            InternalServicesHealthIndicator indicator = new InternalServicesHealthIndicator(
                    WebClient.create(),
                    Map.of("identity", baseUri + "/ready", "match", baseUri + "/down"));

            Health health = indicator.health().block(Duration.ofSeconds(5));

            assertThat(health).isNotNull();
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsEntry("identity", "UP").containsEntry("match", "DOWN");
        } finally {
            server.disposeNow();
        }
    }

    private static DisposableServer readinessServer() {
        return HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes
                        .get("/ready", (request, response) -> response.status(200).send())
                        .get("/down", (request, response) -> response.status(503).send()))
                .bindNow();
    }
}
