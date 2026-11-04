package dev.campusflow;

import static org.assertj.core.api.Assertions.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class RateLimiterTest {
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
    @Test void fixedWindowsResetAndAccountsRemainIndependent() {
        var clock = new MutableClock(); var limiter = new RequestRateLimiter(clock, 1);
        for (int i = 0; i < 10; i++) assertThat(limiter.allow("student", 10)).isTrue();
        assertThat(limiter.allow("student", 10)).isFalse(); assertThat(limiter.allow("another", 10)).isTrue();
        clock.now = clock.now.plusSeconds(60); assertThat(limiter.allow("student", 10)).isTrue();
    }
    @Test void loginRequestsReturn429WithRetryAfter() throws Exception {
        var limiter = new RequestRateLimiter(Clock.systemUTC(), 1);
        for (int i = 0; i < 11; i++) {
            var req = new MockHttpServletRequest("POST", "/api/auth/login"); req.setServletPath("/api/auth/login"); req.setParameter("username", "alice");
            var res = new MockHttpServletResponse(); limiter.doFilter(req, res, new MockFilterChain());
            if (i == 10) { assertThat(res.getStatus()).isEqualTo(429); assertThat(res.getHeader("Retry-After")).isEqualTo("60"); }
        }
    }
}
