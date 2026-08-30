package lol.pinkward.showdown.identity;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public final class LoginAttemptGuard extends OncePerRequestFilter {
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final ConcurrentHashMap<String, AttemptWindow> attempts = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration failureWindow;
    private final Duration lockDuration;
    private final Clock clock;
    private final Counter failures;
    private final Counter lockouts;

    @Autowired
    public LoginAttemptGuard(
            @Value("${pinkward.login-protection.max-failures:5}") int maxFailures,
            @Value("${pinkward.login-protection.failure-window:15m}") Duration failureWindow,
            @Value("${pinkward.login-protection.lock-duration:15m}") Duration lockDuration,
            MeterRegistry meterRegistry) {
        this(maxFailures, failureWindow, lockDuration, Clock.systemUTC(), meterRegistry);
    }

    LoginAttemptGuard(
            int maxFailures,
            Duration failureWindow,
            Duration lockDuration,
            Clock clock,
            MeterRegistry meterRegistry) {
        if (maxFailures < 1 || failureWindow.isNegative() || failureWindow.isZero()
                || lockDuration.isNegative() || lockDuration.isZero()) {
            throw new IllegalArgumentException("Invalid login protection settings");
        }
        this.maxFailures = maxFailures;
        this.failureWindow = failureWindow;
        this.lockDuration = lockDuration;
        this.clock = clock;
        this.failures = meterRegistry.counter("pinkward.identity.login.failures");
        this.lockouts = meterRegistry.counter("pinkward.identity.login.lockouts");
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equalsIgnoreCase(request.getMethod()) || !"/login".equals(request.getServletPath());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        AttemptWindow window = attempts.get(key(request));
        Instant now = clock.instant();
        if (window != null && window.blockedUntil(now).isAfter(now)) {
            lockouts.increment();
            response.setStatus(429);
            response.setHeader("Retry-After", Long.toString(Math.max(1, lockDuration.toSeconds())));
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Trop de tentatives de connexion. Réessayez plus tard.");
            return;
        }
        filterChain.doFilter(request, response);
    }

    void recordFailure(HttpServletRequest request) {
        failures.increment();
        if (attempts.size() >= MAX_TRACKED_KEYS) {
            Instant now = clock.instant();
            attempts.entrySet().removeIf(entry -> entry.getValue().expired(now, failureWindow, lockDuration));
        }
        attempts.computeIfAbsent(key(request), ignored -> new AttemptWindow())
                .fail(clock.instant(), failureWindow, lockDuration, maxFailures);
    }

    void recordSuccess(HttpServletRequest request) {
        attempts.remove(key(request));
    }

    boolean blocked(HttpServletRequest request) {
        AttemptWindow window = attempts.get(key(request));
        Instant now = clock.instant();
        return window != null && window.blockedUntil(now).isAfter(now);
    }

    private static String key(HttpServletRequest request) {
        String username = request.getParameter("username");
        if (username == null) username = "";
        username = username.strip().toLowerCase(Locale.ROOT);
        if (username.length() > 128) username = username.substring(0, 128);
        return request.getRemoteAddr() + '|' + username;
    }

    private static final class AttemptWindow {
        private final ArrayDeque<Instant> failures = new ArrayDeque<>();
        private Instant lockedUntil = Instant.EPOCH;

        synchronized void fail(Instant now, Duration window, Duration lockDuration, int maximum) {
            Instant cutoff = now.minus(window);
            while (!failures.isEmpty() && failures.peekFirst().isBefore(cutoff)) failures.removeFirst();
            failures.addLast(now);
            if (failures.size() >= maximum) {
                lockedUntil = now.plus(lockDuration);
                failures.clear();
            }
        }

        synchronized Instant blockedUntil(Instant now) {
            if (!lockedUntil.isAfter(now)) lockedUntil = Instant.EPOCH;
            return lockedUntil;
        }

        synchronized boolean expired(Instant now, Duration window, Duration lockDuration) {
            return !lockedUntil.isAfter(now) &&
                    (failures.isEmpty() || failures.peekLast().isBefore(now.minus(window).minus(lockDuration)));
        }
    }
}
