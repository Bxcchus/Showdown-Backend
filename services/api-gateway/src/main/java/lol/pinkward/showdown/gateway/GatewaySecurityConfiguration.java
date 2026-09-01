package lol.pinkward.showdown.gateway;

import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.http.HttpMethod;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

@Configuration
public class GatewaySecurityConfiguration {

    @Bean
    KeyResolver watcherKeyResolver() {
        return exchange -> {
            String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
            String remote = exchange.getRequest().getRemoteAddress() == null ? "unknown"
                    : exchange.getRequest().getRemoteAddress().getAddress().getHostAddress();
            return reactor.core.publisher.Mono.just("watcher:" + remote + ":" + sha256(authorization));
        };
    }

    @Bean
    SecurityWebFilterChain gatewaySecurity(ServerHttpSecurity http) {
        return http.csrf(ServerHttpSecurity.CsrfSpec::disable)
                .cors(Customizer.withDefaults())
                .authorizeExchange(authorize -> authorize
                        .pathMatchers("/", "/index.html", "/favicon.svg")
                        .permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                        .permitAll()
                        .pathMatchers("/api/v2/realtime/party")
                        .hasAuthority("SCOPE_party:manage")
                        .pathMatchers("/api/v2/realtime/matches")
                        .hasAuthority("SCOPE_match:read")
                        .pathMatchers("/api/v2/watchers/duels/**")
                        .hasAuthority("SCOPE_service:duel:observe")
                        .pathMatchers("/api/v2/watchers/matches/**")
                        .hasAuthority("SCOPE_service:match:observe")
                        .pathMatchers(HttpMethod.GET, "/api/v2/players/**")
                        .hasAuthority("SCOPE_profile:read")
                        .pathMatchers("/api/v2/players/**")
                        .hasAuthority("SCOPE_profile:write")
                        .pathMatchers("/api/v2/parties/**")
                        .hasAuthority("SCOPE_party:manage")
                        .pathMatchers("/api/v2/matchmaking/**")
                        .hasAuthority("SCOPE_queue:write")
                        .pathMatchers(HttpMethod.GET, "/api/v2/matches/current", "/api/v2/matches/current-lobby",
                                "/api/v2/matches/history", "/api/v2/matches/history/**",
                                "/api/v2/matches/statistics", "/api/v2/matches/duel/statistics",
                                "/api/v2/matches/duel/leaderboard", "/api/v2/matches/seasons")
                        .hasAuthority("SCOPE_match:read")
                        .pathMatchers(HttpMethod.GET, "/api/v2/matches/leaderboard")
                        .hasAuthority("SCOPE_match:read")
                        .pathMatchers(HttpMethod.GET, "/api/v2/matches/duels/**")
                        .hasAuthority("SCOPE_match:read")
                        .pathMatchers("/api/v2/matches/duels/**")
                        .hasAuthority("SCOPE_match:ready")
                        .pathMatchers("/api/v2/matches/*/result")
                        .hasAuthority("SCOPE_service:match:result")
                        .pathMatchers("/api/v2/matches/*/bot-assignment", "/api/v2/matches/*/bot-result",
                                "/api/v2/matches/*/bot-cancel")
                        .hasAuthority("SCOPE_service:match:bot-result")
                        .pathMatchers(HttpMethod.POST, "/api/v2/matches/*/watcher-token")
                        .hasAuthority("SCOPE_match:read")
                        .pathMatchers("/api/v2/matches/*/ready", "/api/v2/matches/*/complete")
                        .hasAuthority("SCOPE_match:ready")
                        .anyExchange()
                        .denyAll())
                .oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()))
                .build();
    }

    @Bean
    ReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
            @Value("${pinkward.security.audience}") String requiredAudience) {
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSetUri(jwkSetUri).build();
        OAuth2TokenValidator<Jwt> issuerAndTimestamps = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audience = token -> token.getAudience().contains(requiredAudience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Required audience is missing", null));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerAndTimestamps, audience));
        return decoder;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "missing" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
