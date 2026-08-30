package lol.pinkward.showdown.identity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

class AuthorizationServerConfigurationTest {

    @Test
    void registersPublicPkceAndDedicatedTechnicalClients() {
        AuthorizationServerConfiguration configuration = new AuthorizationServerConfiguration();
        var clients = configuration.registeredClientDefinitions(
                configuration.passwordEncoder(),
                "http://localhost:8088/oauth/callback",
                "http://127.0.0.1:3000/oauth/callback",
                "technical-secret",
                "player-secret",
                "match-secret",
                "result-ingestor-secret",
                "watcher-secret-with-at-least-32-characters",
                true,
                "pinkward-watcher-installation-desktop01=installation-secret-with-at-least-32-characters");

        RegisteredClient web = clients.stream().filter(client -> client.getClientId().equals("pinkward-web")).findFirst().orElseThrow();
        assertThat(web).isNotNull();
        assertThat(web.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(web.getAuthorizationGrantTypes())
                .contains(AuthorizationGrantType.AUTHORIZATION_CODE, AuthorizationGrantType.REFRESH_TOKEN);
        assertThat(web.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(web.getRedirectUris())
                .containsExactlyInAnyOrder(
                        "http://localhost:8088/oauth/callback",
                        "http://127.0.0.1:3000/oauth/callback");
        assertThat(web.getScopes()).containsExactlyInAnyOrder(
                "openid", "profile:read", "profile:write", "party:manage", "queue:write", "match:read", "match:ready");

        RegisteredClient matchmaking = clients.stream().filter(client -> client.getClientId().equals("pinkward-matchmaking")).findFirst().orElseThrow();
        assertThat(matchmaking).isNotNull();
        assertThat(matchmaking.getAuthorizationGrantTypes())
                .containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(matchmaking.getScopes()).containsExactly("service:match:create");

        RegisteredClient player = clients.stream().filter(client -> client.getClientId().equals("pinkward-player")).findFirst().orElseThrow();
        assertThat(player).isNotNull();
        assertThat(player.getAuthorizationGrantTypes())
                .containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(player.getScopes()).containsExactly("service:queue:party");

        RegisteredClient match = clients.stream().filter(client -> client.getClientId().equals("pinkward-match")).findFirst().orElseThrow();
        assertThat(match.getAuthorizationGrantTypes())
                .containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(match.getScopes()).containsExactly("service:profile:read");

        RegisteredClient resultIngestor = clients.stream()
                .filter(client -> client.getClientId().equals("pinkward-result-ingestor"))
                .findFirst().orElseThrow();
        assertThat(resultIngestor.getAuthorizationGrantTypes())
                .containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(resultIngestor.getScopes()).containsExactly("service:match:result");

        RegisteredClient watcher = clients.stream().filter(client -> client.getClientId().equals("pinkward-watcher")).findFirst().orElseThrow();
        assertThat(watcher.getAuthorizationGrantTypes())
                .containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
        assertThat(watcher.getScopes()).containsExactlyInAnyOrder(
                "service:duel:observe", "service:match:bot-result");
        assertThat(watcher.getScopes()).doesNotContain("service:match:result");
        RegisteredClient installedWatcher = clients.stream()
                .filter(client -> client.getClientId().equals("pinkward-watcher-installation-desktop01"))
                .findFirst().orElseThrow();
        assertThat(installedWatcher.getScopes()).containsExactly("service:duel:observe");
        assertThat(installedWatcher.getScopes())
                .doesNotContain("service:profile:link", "service:match:bot-result", "service:match:result");
        assertThat(web.getScopes()).doesNotContain("match:result", "service:match:result");
    }
}
