package lol.pinkward.showdown.player;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
class PartyQueueClient {

    private final RestClient restClient;
    private final URI tokenUri;
    private final URI matchmakingUri;
    private final String clientId;
    private final String clientSecret;

    PartyQueueClient(
            @Value("${pinkward.party.queue.token-uri}") URI tokenUri,
            @Value("${pinkward.party.queue.matchmaking-uri}") URI matchmakingUri,
            @Value("${pinkward.party.queue.client-id}") String clientId,
            @Value("${pinkward.party.queue.client-secret}") String clientSecret) {
        this.restClient = strictClient();
        this.tokenUri = tokenUri;
        this.matchmakingUri = matchmakingUri;
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

    void join(UUID partyId, String region, List<Member> members, String idempotencyKey) {
        try {
            restClient.post()
                    .uri(matchmakingUri.resolve("/internal/v1/matchmaking/parties/" + partyId))
                    .header("Authorization", "Bearer " + accessToken())
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("region", region, "members", members))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw new PartyQueueException(exception.getStatusCode().value(), "Group queue request was rejected", exception);
        }
    }

    void leave(UUID partyId) {
        try {
            restClient.delete()
                    .uri(matchmakingUri.resolve("/internal/v1/matchmaking/parties/" + partyId))
                    .header("Authorization", "Bearer " + accessToken())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException exception) {
            throw new PartyQueueException(exception.getStatusCode().value(), "Group queue cancellation was rejected", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private String accessToken() {
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        form.add("scope", "service:queue:party");
        Map<String, Object> response = restClient.post()
                .uri(tokenUri)
                .headers(headers -> headers.setBasicAuth(clientId, clientSecret))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(Map.class);
        if (response == null || !(response.get("access_token") instanceof String token)) {
            throw new IllegalStateException("Identity did not issue a service token");
        }
        return token;
    }

    record Member(UUID playerId, String primaryRole, String secondaryRole, boolean simulated) {}

    static class PartyQueueException extends RuntimeException {
        private final int status;
        PartyQueueException(int status, String message, Throwable cause) {
            super(message, cause);
            this.status = status;
        }
        int status() { return status; }
    }
}
