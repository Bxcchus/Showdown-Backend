package lol.pinkward.showdown.identity;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.oauth2.server.authorization.client.JdbcRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
public class AuthorizationServerConfiguration {

    @Bean
    FilterRegistrationBean<LoginAttemptGuard> disableContainerRegistration(LoginAttemptGuard guard) {
        FilterRegistrationBean<LoginAttemptGuard> registration = new FilterRegistrationBean<>(guard);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(
            HttpSecurity http,
            @Value("${pinkward.external-identity.enabled:false}") boolean externalIdentityEnabled,
            @Value("${pinkward.external-identity.registration-id:production}") String registrationId)
            throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServer =
                new OAuth2AuthorizationServerConfigurer();
        RequestMatcher endpoints = authorizationServer.getEndpointsMatcher();
        http.securityMatcher(endpoints)
                .with(authorizationServer, server -> server
                        .oidc(Customizer.withDefaults())
                        .authorizationEndpoint(endpoint -> endpoint.consentPage("/oauth2/consent")))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint(loginUrl(externalIdentityEnabled, registrationId)),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain applicationSecurityFilterChain(
            HttpSecurity http,
            LoginAttemptGuard loginAttemptGuard,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
            @Value("${pinkward.external-identity.enabled:false}") boolean externalIdentityEnabled,
            @Value("${pinkward.external-identity.registration-id:production}") String registrationId)
            throws Exception {
        SavedRequestAwareAuthenticationSuccessHandler successHandler =
                new SavedRequestAwareAuthenticationSuccessHandler();
        SimpleUrlAuthenticationFailureHandler failureHandler =
                new SimpleUrlAuthenticationFailureHandler("/login?error");
        http.authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/login", "/identity.css", "/identity.js", "/fonts/**",
                                "/oauth2/authorization/**", "/login/oauth2/code/**",
                                "/actuator/health/**", "/actuator/info", "/actuator/prometheus")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .addFilterBefore(loginAttemptGuard, UsernamePasswordAuthenticationFilter.class);
        if (externalIdentityEnabled) {
            if (clientRegistrations.getIfAvailable() == null) {
                throw new IllegalStateException("External identity is enabled without an OIDC client registration");
            }
            http.oauth2Login(oauth -> oauth
                    .loginPage(loginUrl(true, registrationId))
                    .userInfoEndpoint(userInfo -> userInfo
                            .userAuthoritiesMapper(oidcAuthorizationCodeAuthorityMapper()))
                    .successHandler(successHandler));
        } else {
            http.formLogin(form -> form.loginPage("/login")
                        .successHandler((request, response, authentication) -> {
                            loginAttemptGuard.recordSuccess(request);
                            successHandler.onAuthenticationSuccess(request, response, authentication);
                        })
                        .failureHandler((request, response, exception) -> {
                            loginAttemptGuard.recordFailure(request);
                            failureHandler.onAuthenticationFailure(request, response, exception);
                        }));
        }
        return http.build();
    }

    /*
     * Spring Security 7.1.1 does not add the authorization-code factor to an
     * OIDC login. Spring Authorization Server needs that factor to derive the
     * auth_time of the ID token it issues. Keep this mapper until the upstream
     * OidcAuthorizationCodeAuthenticationProvider fix is available.
     */
    static GrantedAuthoritiesMapper oidcAuthorizationCodeAuthorityMapper() {
        return authorities -> {
            Set<GrantedAuthority> mapped = new LinkedHashSet<>(authorities);
            mapped.add(FactorGrantedAuthority.fromAuthority(
                    FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY));
            return mapped;
        };
    }

    @Bean
    RegisteredClientRepository registeredClients(
            JdbcOperations jdbcOperations,
            PasswordEncoder passwordEncoder,
            @Value("${pinkward.clients.web.secret}") String webSecret,
            @Value("${pinkward.clients.web.redirect-uri}") String webRedirectUri,
            @Value("${pinkward.clients.web.development-redirect-uri}") String webDevelopmentRedirectUri,
            @Value("${pinkward.clients.matchmaking.secret}") String matchmakingSecret,
            @Value("${pinkward.clients.player.secret}") String playerSecret,
            @Value("${pinkward.clients.match.secret}") String matchSecret,
            @Value("${pinkward.clients.result-ingestor.secret}") String resultIngestorSecret,
            @Value("${pinkward.clients.watcher.secret:}") String watcherSecret,
            @Value("${pinkward.clients.watcher.local-enabled:true}") boolean localWatcherEnabled,
            @Value("${pinkward.clients.watcher.installations:}") String watcherInstallations) {
        JdbcRegisteredClientRepository repository = new JdbcRegisteredClientRepository(jdbcOperations);
        List<RegisteredClient> configuredClients = registeredClientDefinitions(
                passwordEncoder, webSecret, webRedirectUri, webDevelopmentRedirectUri,
                matchmakingSecret, playerSecret, matchSecret, resultIngestorSecret, watcherSecret,
                localWatcherEnabled, watcherInstallations);
        configuredClients.forEach(client -> {
                    RegisteredClient existing = repository.findByClientId(client.getClientId());
                    if (existing == null) {
                        repository.save(client);
                        return;
                    }
                    RegisteredClient.Builder reconciled = RegisteredClient.from(existing)
                            .clientName(client.getClientName())
                            .scopes(scopes -> {
                                scopes.clear();
                                scopes.addAll(client.getScopes());
                            })
                            .clientSettings(client.getClientSettings())
                            .tokenSettings(client.getTokenSettings());
                    if ("pinkward-web".equals(client.getClientId())) {
                        reconciled.clientSecret(client.getClientSecret())
                                .clientAuthenticationMethods(methods -> {
                                    methods.clear();
                                    methods.addAll(client.getClientAuthenticationMethods());
                                })
                                .authorizationGrantTypes(grantTypes -> {
                                    grantTypes.clear();
                                    grantTypes.addAll(client.getAuthorizationGrantTypes());
                                });
                        reconciled.redirectUris(uris -> {
                            uris.clear();
                            uris.addAll(client.getRedirectUris());
                        });
                    } else {
                        // Reconcile confidential clients too: otherwise a rotated secret or a newly
                        // introduced service scope never reaches an already initialized database.
                        reconciled.clientSecret(client.getClientSecret());
                    }
                    repository.save(reconciled.build());
                });
        revokeUnconfiguredWatcherClients(jdbcOperations, configuredClients);
        return repository;
    }

    List<RegisteredClient> registeredClientDefinitions(
            PasswordEncoder passwordEncoder,
            String webSecret,
            String webRedirectUri,
            String webDevelopmentRedirectUri,
            String matchmakingSecret,
            String playerSecret,
            String matchSecret,
            String resultIngestorSecret,
            String watcherSecret,
            boolean localWatcherEnabled,
            String watcherInstallations) {
        TokenSettings publicTokenSettings = TokenSettings.builder()
                .accessTokenTimeToLive(Duration.ofMinutes(10))
                .refreshTokenTimeToLive(Duration.ofDays(14))
                .reuseRefreshTokens(false)
                .build();
        if (webSecret == null || webSecret.length() < 32) {
            throw new IllegalArgumentException("Web BFF secret must contain at least 32 characters");
        }
        RegisteredClient.Builder webBuilder = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("pinkward-web")
                .clientName("GYMS.LOL Web")
                .clientSecret(passwordEncoder.encode(webSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(webRedirectUri);
        if (webDevelopmentRedirectUri != null && !webDevelopmentRedirectUri.isBlank()) {
            webBuilder.redirectUri(webDevelopmentRedirectUri);
        }
        RegisteredClient web = webBuilder
                .scope("openid")
                .scope("profile:read")
                .scope("profile:write")
                .scope("party:manage")
                .scope("queue:write")
                .scope("match:read")
                .scope("match:ready")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        // GYMS.LOL Web is the first-party UI and always needs this exact,
                        // server-controlled scope set. A second consent screen after Auth0
                        // adds no meaningful choice and can strand the browser mid-login.
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(publicTokenSettings)
                .build();
        RegisteredClient matchmaking = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("pinkward-matchmaking")
                .clientSecret(passwordEncoder.encode(matchmakingSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("service:match:create")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(5))
                        .build())
                .build();
        RegisteredClient player = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("pinkward-player")
                .clientSecret(passwordEncoder.encode(playerSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("service:queue:party")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(5))
                        .build())
                .build();
        RegisteredClient match = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("pinkward-match")
                .clientSecret(passwordEncoder.encode(matchSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("service:profile:read")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(5))
                        .build())
                .build();
        RegisteredClient resultIngestor = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId("pinkward-result-ingestor")
                .clientSecret(passwordEncoder.encode(resultIngestorSecret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("service:match:result")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(2))
                        .build())
                .build();
        List<RegisteredClient> clients = new ArrayList<>(List.of(
                web, matchmaking, player, match, resultIngestor));
        if (localWatcherEnabled) {
            if (watcherSecret == null || watcherSecret.length() < 32) {
                throw new IllegalArgumentException("Local watcher secret must contain at least 32 characters");
            }
            clients.add(watcherClient(passwordEncoder, "pinkward-watcher", watcherSecret, true));
        }
        parseWatcherInstallations(watcherInstallations).forEach(credential ->
                clients.add(watcherClient(passwordEncoder, credential.clientId(), credential.secret(), false)));
        return List.copyOf(clients);
    }

    private static RegisteredClient watcherClient(
            PasswordEncoder passwordEncoder, String clientId, String secret, boolean local) {
        RegisteredClient.Builder client = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(clientId)
                .clientSecret(passwordEncoder.encode(secret))
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scope("service:duel:observe")
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofMinutes(2))
                        .build());
        if (local) client.scope("service:match:bot-result");
        return client.build();
    }

    private static List<WatcherInstallationCredential> parseWatcherInstallations(String value) {
        if (value == null || value.isBlank()) return List.of();
        Set<String> ids = new HashSet<>();
        List<WatcherInstallationCredential> credentials = new ArrayList<>();
        for (String item : value.split(",")) {
            String[] parts = item.trim().split("=", 2);
            if (parts.length != 2
                    || !parts[0].matches("pinkward-watcher-installation-[A-Za-z0-9_-]{8,64}")
                    || parts[1].length() < 32
                    || !ids.add(parts[0])) {
                throw new IllegalArgumentException("Invalid or duplicate watcher installation credential");
            }
            credentials.add(new WatcherInstallationCredential(parts[0], parts[1]));
        }
        return List.copyOf(credentials);
    }

    private static void revokeUnconfiguredWatcherClients(
            JdbcOperations jdbcOperations, List<RegisteredClient> configuredClients) {
        Set<String> configuredIds = configuredClients.stream()
                .map(RegisteredClient::getClientId)
                .filter(clientId -> clientId.equals("pinkward-watcher")
                        || clientId.startsWith("pinkward-watcher-installation-"))
                .collect(java.util.stream.Collectors.toSet());
        jdbcOperations.query(
                "select id, client_id from oauth2_registered_client "
                        + "where client_id = 'pinkward-watcher' "
                        + "or client_id like 'pinkward-watcher-installation-%'",
                (resultSet, row) -> new String[] { resultSet.getString("id"), resultSet.getString("client_id") })
                .stream()
                .filter(entry -> !configuredIds.contains(entry[1]))
                .forEach(entry -> {
                    jdbcOperations.update("delete from oauth2_authorization_consent where registered_client_id = ?", entry[0]);
                    jdbcOperations.update("delete from oauth2_authorization where registered_client_id = ?", entry[0]);
                    jdbcOperations.update("delete from oauth2_registered_client where id = ?", entry[0]);
                });
    }

    private record WatcherInstallationCredential(String clientId, String secret) {}

    @Bean
    UserDetailsService users(
            PasswordEncoder passwordEncoder,
            @Value("${pinkward.local-user.enabled:true}") boolean enabled,
            @Value("${pinkward.local-user.name}") String username,
            @Value("${pinkward.local-user.password}") String password,
            @Value("${pinkward.local-user.secondary-name:}") String secondaryUsername,
            @Value("${pinkward.local-user.secondary-password:}") String secondaryPassword) {
        var users = new java.util.ArrayList<org.springframework.security.core.userdetails.UserDetails>();
        if (enabled) {
            users.add(User.withUsername(username)
                    .password(passwordEncoder.encode(password))
                    .roles("USER")
                    .build());
        }
        if (enabled && !secondaryUsername.isBlank() && !secondaryPassword.isBlank()) {
            users.add(User.withUsername(secondaryUsername)
                    .password(passwordEncoder.encode(secondaryPassword))
                    .roles("USER")
                    .build());
        }
        return new org.springframework.security.provisioning.InMemoryUserDetailsManager(
                users);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    AuthorizationServerSettings authorizationServerSettings(
            @Value("${pinkward.issuer}") String issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }

    @Bean
    OAuth2TokenCustomizer<JwtEncodingContext> accessTokenClaims(
            @Value("${pinkward.api-audience}") String audience,
            PersistentJwkSource signingKeys) {
        return context -> {
            context.getJwsHeader().keyId(signingKeys.activeKeyId());
            if (AuthorizationGrantType.AUTHORIZATION_CODE.equals(context.getAuthorizationGrantType())) {
                PlayerIdentity identity = playerIdentity(context.getPrincipal());
                context.getClaims().subject(identity.playerId().toString());
                context.getClaims().claim("preferred_username", identity.displayName());
            }
            if (OAuth2TokenType.ACCESS_TOKEN.equals(context.getTokenType())) {
                context.getClaims().audience(List.of(audience));
                context.getClaims().id(UUID.randomUUID().toString());
                if (context.getPrincipal() != null) {
                    context.getClaims().claim(
                            "roles",
                            context.getPrincipal().getAuthorities().stream()
                                    .map(authority -> authority.getAuthority())
                                    .filter(authority -> authority.startsWith("ROLE_"))
                                    .map(authority -> authority.substring(5))
                                    .toList());
                }
            }
        };
    }

    static String loginUrl(boolean externalIdentityEnabled, String registrationId) {
        if (!externalIdentityEnabled) return "/login";
        if (registrationId == null || !registrationId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("Invalid external identity registration id");
        }
        return "/oauth2/authorization/" + registrationId;
    }

    static PlayerIdentity playerIdentity(Authentication authentication) {
        if (authentication instanceof OAuth2AuthenticationToken oauth) {
            if (!(oauth.getPrincipal() instanceof OidcUser oidcUser)) {
                throw new IllegalArgumentException("External identity principal must be an OIDC user");
            }
            String issuer = oidcUser.getIssuer() == null ? null : oidcUser.getIssuer().toExternalForm();
            String providerSubject = oidcUser.getSubject();
            if (issuer == null || issuer.isBlank() || providerSubject == null || providerSubject.isBlank()) {
                throw new IllegalArgumentException("OIDC identity requires non-empty issuer and subject claims");
            }
            UUID playerId = UUID.nameUUIDFromBytes(
                    ("pinkward-oidc-player:" + issuer + '\0' + providerSubject)
                            .getBytes(StandardCharsets.UTF_8));
            return new PlayerIdentity(playerId, externalDisplayName(playerId));
        }
        String username = authentication.getName();
        UUID playerId = UUID.nameUUIDFromBytes(
                ("pinkward-local-player:" + username).getBytes(StandardCharsets.UTF_8));
        return new PlayerIdentity(playerId, username);
    }

    private static String externalDisplayName(UUID playerId) {
        // The OIDC name can be an email address. Keep it out of access tokens and
        // consent screens until the player explicitly chooses a public nickname.
        return "Player-" + playerId.toString().substring(0, 8);
    }

    record PlayerIdentity(UUID playerId, String displayName) {}

}
