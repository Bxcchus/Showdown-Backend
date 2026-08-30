package lol.pinkward.showdown.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class JwtValidationTest {
    private static final String ISSUER = "https://showdown.example.test";

    @Test
    void rejectsAnExpiredAccessToken() {
        Instant now = Instant.now();
        Jwt expired = jwt(now.minusSeconds(600), now.minusSeconds(300), List.of("pinkward-api"));
        assertThat(SecurityConfiguration.tokenValidator(ISSUER, "pinkward-api").validate(expired).hasErrors())
                .isTrue();
    }

    @Test
    void rejectsATokenIssuedForAnotherAudience() {
        Instant now = Instant.now();
        Jwt wrongAudience = jwt(now.minusSeconds(10), now.plusSeconds(60), List.of("another-api"));
        assertThat(SecurityConfiguration.tokenValidator(ISSUER, "pinkward-api").validate(wrongAudience).hasErrors())
                .isTrue();
    }

    private static Jwt jwt(Instant issuedAt, Instant expiresAt, List<String> audience) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuer(ISSUER)
                .subject("00000000-0000-4000-8000-000000000001")
                .audience(audience)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
    }
}
