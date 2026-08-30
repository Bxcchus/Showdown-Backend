package lol.pinkward.showdown.identity;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.security.Principal;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.util.HtmlUtils;

@Controller
class IdentityUiController {

    private static final Map<String, PermissionCopy> PERMISSIONS = Map.of(
            "profile:read", new PermissionCopy("View your profile", "Read your Pinkward identity, region and preferred roles."),
            "profile:write", new PermissionCopy("Update your profile", "Keep your presence and matchmaking preferences synchronized."),
            "party:manage", new PermissionCopy("Manage your party", "Create parties, invite players and update ready states."),
            "queue:write", new PermissionCopy("Join matchmaking", "Enter or leave the 1v1 and 5v5 competitive queues."),
            "match:read", new PermissionCopy("View your matches", "Access ready checks, lobbies, history and rankings."),
            "match:ready", new PermissionCopy("Answer ready checks", "Accept or decline matches found for your account."));

    private final RegisteredClientRepository clients;
    private final boolean externalIdentityEnabled;
    private final String externalLoginUrl;

    IdentityUiController(
            RegisteredClientRepository clients,
            @Value("${pinkward.external-identity.enabled:false}") boolean externalIdentityEnabled,
            @Value("${pinkward.external-identity.registration-id:production}") String registrationId) {
        this.clients = clients;
        this.externalIdentityEnabled = externalIdentityEnabled;
        this.externalLoginUrl = AuthorizationServerConfiguration.loginUrl(externalIdentityEnabled, registrationId);
    }

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> login(
            HttpServletRequest request,
            @RequestParam(required = false) String error,
            @RequestParam(required = false) String logout) {
        if (externalIdentityEnabled) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(externalLoginUrl)).build();
        }
        CsrfToken csrf = csrf(request);
        String notice = error != null
                ? "<div class=\"auth-notice auth-notice--error\">"
                        + "Incorrect username or password. For local development, use the credentials "
                        + "currently generated in <strong>infra/.env</strong>."
                        + "</div>"
                : logout != null
                        ? "<div class=\"auth-notice\">You have been signed out.</div>"
                        : "";
        String html = page("Sign in", """
                <main class="identity-page identity-page--login">
                  <section class="identity-card login-card">
                    %s
                    <div class="identity-brand"><span class="brand-mark">P</span><strong>PINKWARD</strong></div>
                    <div class="identity-heading">
                      <span>SECURE ACCESS</span>
                      <h1>WELCOME BACK</h1>
                      <p>Sign in to continue to Pinkward competitive services.</p>
                    </div>
                    %s
                    <form method="post" action="/login" class="identity-form">
                      <input type="hidden" name="%s" value="%s">
                      <label><span>USERNAME</span><input name="username" type="text" autocomplete="username" autofocus required placeholder="Pinkward ID"></label>
                      <label><span>PASSWORD</span><input name="password" type="password" autocomplete="current-password" required placeholder="••••••••••••"></label>
                      <button class="gold-button" type="submit">SIGN IN</button>
                    </form>
                    <div class="identity-meta"><span><i></i> LOCAL IDENTITY SERVER</span><b>OAUTH 2.1 · PKCE</b></div>
                  </section>
                </main>
                """.formatted(backgroundArt(), notice, escape(csrf.getParameterName()), escape(csrf.getToken())));
        return html(html);
    }

    @GetMapping(value = "/oauth2/consent", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> consent(
            HttpServletRequest request,
            Principal principal,
            @RequestParam("client_id") String clientId,
            @RequestParam("scope") String scope,
            @RequestParam("state") String state) {
        RegisteredClient client = clients.findByClientId(clientId);
        String clientName = client == null || client.getClientName() == null
                ? clientId
                : client.getClientName();
        Set<String> scopes = new LinkedHashSet<>(Arrays.asList(scope.split("\\s+")));
        scopes.remove("openid");
        String permissions = scopes.stream().map(this::permission).collect(Collectors.joining());
        CsrfToken csrf = csrf(request);
        String html = page("Authorize Pinkward", """
                <main class="identity-page identity-page--consent">
                  <section class="identity-card consent-card">
                    %s
                    <div class="identity-brand"><span class="brand-mark">P</span><strong>PINKWARD</strong></div>
                    <div class="identity-heading">
                      <span>AUTHORIZATION REQUEST</span>
                      <h1>CONNECT YOUR ACCOUNT</h1>
                      <p><strong>%s</strong> is requesting access to <b>%s</b>.</p>
                    </div>
                    <form method="post" action="/oauth2/authorize" name="consent_form" class="consent-form">
                      <input type="hidden" name="client_id" value="%s">
                      <input type="hidden" name="state" value="%s">
                      <input type="hidden" name="%s" value="%s">
                      <div class="permission-list">%s</div>
                      <p class="consent-copy">You stay in control. Pinkward only receives the permissions selected here.</p>
                      <div class="consent-actions">
                        <button class="gold-button" type="submit">AUTHORIZE PINKWARD</button>
                        <button class="ghost-button" type="button" data-consent-cancel>CANCEL</button>
                      </div>
                    </form>
                    <div class="identity-meta"><span><i></i> SIGNED IN AS %s</span><b>OAUTH 2.1 · PKCE</b></div>
                  </section>
                </main>
                """.formatted(backgroundArt(), escape(clientName), escape(principal.getName()), escape(clientId),
                escape(state), escape(csrf.getParameterName()), escape(csrf.getToken()), permissions,
                escape(principal.getName().toUpperCase(Locale.ROOT))));
        return html(html);
    }

    private String permission(String scope) {
        PermissionCopy copy = PERMISSIONS.getOrDefault(scope,
                new PermissionCopy(scope, "Allow Pinkward to use this permission."));
        return """
                <label class="permission-row">
                  <input type="checkbox" name="scope" value="%s" checked>
                  <span class="permission-check">✓</span>
                  <span><strong>%s</strong><small>%s</small></span>
                </label>
                """.formatted(escape(scope), escape(copy.title()), escape(copy.description()));
    }

    private static CsrfToken csrf(HttpServletRequest request) {
        return (CsrfToken) request.getAttribute(CsrfToken.class.getName());
    }

    private static String backgroundArt() {
        return "<div class=\"identity-art\" aria-hidden=\"true\"><i></i><i></i><i></i></div>";
    }

    private static String page(String title, String body) {
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
                <title>%s · Pinkward</title><link rel="stylesheet" href="/identity.css"><script src="/identity.js" defer></script></head><body>%s</body></html>
                """.formatted(escape(title), body);
    }

    private static ResponseEntity<String> html(String body) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(body);
    }

    private static String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }

    private record PermissionCopy(String title, String description) {}
}
