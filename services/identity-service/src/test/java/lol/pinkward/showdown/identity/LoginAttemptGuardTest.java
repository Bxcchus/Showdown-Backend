package lol.pinkward.showdown.identity;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class LoginAttemptGuardTest {

    @Test
    void locksOnlyTheRepeatedIpAndUsernamePairThenAllowsItAfterTheDelay() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-30T00:00:00Z"));
        LoginAttemptGuard guard = new LoginAttemptGuard(
                3, Duration.ofMinutes(10), Duration.ofMinutes(15), clock, new SimpleMeterRegistry());
        MockHttpServletRequest request = login("203.0.113.10", "local-player");

        guard.recordFailure(request);
        guard.recordFailure(request);
        assertThat(guard.blocked(request)).isFalse();
        guard.recordFailure(request);
        assertThat(guard.blocked(request)).isTrue();
        assertThat(guard.blocked(login("203.0.113.10", "another-player"))).isFalse();
        assertThat(guard.blocked(login("203.0.113.11", "local-player"))).isFalse();

        clock.advance(Duration.ofMinutes(16));
        assertThat(guard.blocked(request)).isFalse();
    }

    @Test
    void successfulAuthenticationClearsPreviousFailures() {
        LoginAttemptGuard guard = new LoginAttemptGuard(
                2, Duration.ofMinutes(10), Duration.ofMinutes(15), Clock.systemUTC(), new SimpleMeterRegistry());
        MockHttpServletRequest request = login("127.0.0.1", "local-player");
        guard.recordFailure(request);
        guard.recordSuccess(request);
        guard.recordFailure(request);
        assertThat(guard.blocked(request)).isFalse();
    }

    private static MockHttpServletRequest login(String address, String username) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
        request.setRemoteAddr(address);
        request.setParameter("username", username);
        return request;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
