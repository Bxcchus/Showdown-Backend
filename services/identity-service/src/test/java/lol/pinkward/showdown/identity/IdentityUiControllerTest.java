package lol.pinkward.showdown.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

class IdentityUiControllerTest {

    private final RegisteredClientRepository clients = mock(RegisteredClientRepository.class);
    private final IdentityUiController controller = new IdentityUiController(clients, false, "production");

    @Test
    void loginUsesGymsIdentityExperience() {
        String body = controller.login(request(), null, null).getBody();

        assertThat(body)
                .contains("WELCOME BACK")
                .contains("GYMS.LOL")
                .doesNotContain("PINKWARD")
                .contains("identity.css")
                .contains("name=\"username\"")
                .contains("name=\"_csrf\" value=\"test-token\"");
    }

    @Test
    void loginErrorPointsLocalDevelopmentToTheRotatedCredentials() {
        String body = controller.login(request(), "invalid", null).getBody();

        assertThat(body)
                .contains("Incorrect username or password")
                .contains("infra/.env")
                .doesNotContain("LOCAL_IDENTITY_PASSWORD=");
    }

    @Test
    void productionLoginRedirectsToTheConfiguredExternalProvider() {
        IdentityUiController externalController = new IdentityUiController(clients, true, "company-oidc");

        var response = externalController.login(request(), null, null);

        assertThat(response.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation())
                .hasToString("/oauth2/authorization/company-oidc");
        assertThat(response.getBody()).isNull();
    }

    @Test
    void consentRendersRequestedPermissionsWithGymsActions() {
        RegisteredClient client = RegisteredClient.withId("test-id")
                .clientId("pinkward-web")
                .clientName("GYMS.LOL Web")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("http://localhost:3000/oauth/callback")
                .build();
        when(clients.findByClientId("pinkward-web")).thenReturn(client);
        Principal principal = () -> "local-player";

        String body = controller.consent(
                request(), principal, "pinkward-web", "openid profile:read queue:write", "state-1").getBody();

        assertThat(body)
                .contains("CONNECT YOUR ACCOUNT")
                .contains("View your profile")
                .contains("Join matchmaking")
                .contains("AUTHORIZE GYMS.LOL")
                .contains("GYMS.LOL Web")
                .contains("SIGNED IN AS LOCAL-PLAYER")
                .doesNotContain("PINKWARD")
                .contains("identity.js")
                .contains("data-consent-cancel")
                .doesNotContain("onclick=")
                .doesNotContain("value=\"openid\"");
    }

    @Test
    void consentShowsAFriendlyExternalIdentityWithoutExposingTheProviderSubject() {
        RegisteredClient client = RegisteredClient.withId("test-id")
                .clientId("pinkward-web")
                .clientName("GYMS.LOL Web")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://gyms.lol/oauth/callback")
                .build();
        when(clients.findByClientId("pinkward-web")).thenReturn(client);
        var user = new DefaultOAuth2User(
                Set.of(new SimpleGrantedAuthority("ROLE_USER")),
                Map.of("sub", "auth0|sensitive-provider-subject", "nickname", "Alexis"),
                "sub");
        Principal principal = new OAuth2AuthenticationToken(user, user.getAuthorities(), "production");

        String body = controller.consent(
                request(), principal, "pinkward-web", "openid profile:read", "state-1").getBody();

        assertThat(body)
                .contains("requesting access to <b>Alexis</b>")
                .contains("SIGNED IN AS ALEXIS")
                .doesNotContain("auth0|sensitive-provider-subject");
    }

    @Test
    void consentScriptLocksRepeatedSubmissionsWithoutDisablingFormControls() throws Exception {
        try (var stream = getClass().getResourceAsStream("/static/identity.js")) {
            assertThat(stream).isNotNull();
            String script = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(script)
                    .contains("if (submitting)")
                    .contains("event.preventDefault()")
                    .contains("aria-disabled")
                    .doesNotContain("button.disabled = true");
        }
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        CsrfToken csrf = new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", "test-token");
        request.setAttribute(CsrfToken.class.getName(), csrf);
        return request;
    }
}
