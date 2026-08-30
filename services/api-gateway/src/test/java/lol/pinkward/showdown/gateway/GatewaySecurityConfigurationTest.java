package lol.pinkward.showdown.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://identity.invalid",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://identity.invalid/oauth2/jwks"
        })
class GatewaySecurityConfigurationTest {

    @LocalServerPort
    int port;

    @Test
    void rejectsWatcherEndpointsWithoutTechnicalAuthentication() {
        WebTestClient.bindToServer()
                .baseUrl("http://127.0.0.1:" + port)
                .build()
                .get()
                .uri("/api/v2/watchers/duels/11111111-1111-4111-8111-111111111111")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
