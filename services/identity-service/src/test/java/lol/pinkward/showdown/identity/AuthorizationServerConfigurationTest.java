package lol.pinkward.showdown.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
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

    @Test
    void selectsTheExternalLoginOnlyWithAValidatedRegistrationId() {
        assertThat(AuthorizationServerConfiguration.loginUrl(false, "ignored")).isEqualTo("/login");
        assertThat(AuthorizationServerConfiguration.loginUrl(true, "company-oidc"))
                .isEqualTo("/oauth2/authorization/company-oidc");
        assertThatThrownBy(() -> AuthorizationServerConfiguration.loginUrl(true, "../login"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void externalIdentityUsesProviderAndSubjectForAStablePlayerId() {
        var principal = new DefaultOAuth2User(
                Set.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("sub", "stable-provider-subject", "preferred_username", "Alexis External"),
                "sub");
        var authentication = new OAuth2AuthenticationToken(
                principal, principal.getAuthorities(), "production");

        var first = AuthorizationServerConfiguration.playerIdentity(authentication);
        var second = AuthorizationServerConfiguration.playerIdentity(authentication);

        assertThat(first.playerId()).isEqualTo(second.playerId());
        assertThat(first.displayName()).startsWith("Alexis External-").hasSizeLessThanOrEqualTo(24);

        var otherProvider = new OAuth2AuthenticationToken(
                principal, principal.getAuthorities(), "other-provider");
        assertThat(AuthorizationServerConfiguration.playerIdentity(otherProvider).playerId())
                .isNotEqualTo(first.playerId());
    }

    @Test
    void localIdentityKeepsTheExistingStableNamespace() {
        var authentication = UsernamePasswordAuthenticationToken.authenticated(
                "local-player", "ignored", Set.of(new SimpleGrantedAuthority("ROLE_USER")));

        var identity = AuthorizationServerConfiguration.playerIdentity(authentication);

        assertThat(identity.displayName()).isEqualTo("local-player");
        assertThat(identity.playerId().toString()).isEqualTo("fab9a498-4a3c-3932-9f52-7cc417980275");
    }
}
