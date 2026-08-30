package lol.pinkward.showdown.match;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Component
class DuelIdentityClient {

    private final RestClient restClient;
    private final URI tokenUri;
    private final URI playerUri;
    private final String clientId;
    private final String clientSecret;

    DuelIdentityClient(
            @Value("${pinkward.duel.identity.token-uri}") URI tokenUri,
            @Value("${pinkward.duel.identity.player-uri}") URI playerUri,
            @Value("${pinkward.duel.identity.client-id}") String clientId,
            @Value("${pinkward.duel.identity.client-secret}") String clientSecret) {
        this.restClient = strictClient();
        this.tokenUri = tokenUri;
        this.playerUri = playerUri;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    private static RestClient strictClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    DuelIdentity resolve(UUID playerId) {
        try {
            DuelIdentity identity = restClient.get()
                    .uri(playerUri.resolve("/internal/v1/players/" + playerId + "/duel-identity"))
                    .header("Authorization", "Bearer " + accessToken())
                    .retrieve()
                    .body(DuelIdentity.class);
            if (identity == null || !playerId.equals(identity.playerId())
                    || identity.puuid() == null || identity.puuid().isBlank()
                    || identity.riotId() == null || identity.region() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Player identity response is invalid");
            }
            return identity;
        } catch (RestClientResponseException exception) {
            HttpStatus status = exception.getStatusCode().value() == 404
                    ? HttpStatus.NOT_FOUND
                    : exception.getStatusCode().value() == 409 ? HttpStatus.CONFLICT : HttpStatus.BAD_GATEWAY;
            throw new ResponseStatusException(status, "Authoritative player identity is unavailable", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private String accessToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("scope", "service:profile:read");
        Map<String, Object> response = restClient.post()
                .uri(tokenUri)
                .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (response == null || !(response.get("access_token") instanceof String token) || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Identity did not issue a service token");
        }
        return token;
    }

    record DuelIdentity(UUID playerId, String puuid, String riotId, String region) {}
}
