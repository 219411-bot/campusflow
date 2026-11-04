package dev.campusflow;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.web.filter.OncePerRequestFilter;

/** Single-process fixed windows. Remote IP is used directly; untrusted proxy headers are ignored. */
class RequestRateLimiter extends OncePerRequestFilter {
    private record Window(long bucket, int count) {}
    private final Map<String, Window> windows = new HashMap<>();
    private final Clock clock;
    private final int multiplier;
    private long lastBucket = Long.MIN_VALUE;
    RequestRateLimiter(Clock clock, int multiplier) { this.clock = clock; this.multiplier = multiplier; }

    synchronized boolean allow(String key, int limit) {
        if (multiplier == 0) return true;
        long bucket = clock.instant().getEpochSecond() / 60;
        if (lastBucket != bucket) { windows.clear(); lastBucket = bucket; }
        Window old = windows.get(key);
        if (old == null && windows.size() >= 10000) return false;
        int count = old == null ? 0 : old.count();
        if (count >= limit * multiplier) return false;
        windows.put(key, new Window(bucket, count + 1)); return true;
    }

    @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws IOException, ServletException {
        String path = req.getServletPath();
        if (!"GET".equals(req.getMethod()) && path.startsWith("/api/")) {
            String address = req.getRemoteAddr();
            boolean allowed;
            if (path.equals("/api/auth/login")) {
                String username = req.getParameter("username");
                allowed = allow("login-ip:" + address, 20) && allow("login-user:" + (username == null ? "" : username.substring(0, Math.min(32, username.length()))), 10);
            } else if (path.equals("/api/auth/register")) allowed = allow("signup:" + address, 5);
            else {
                var auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
                String identity = auth == null || !auth.isAuthenticated() || auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken
                    ? "ip:" + address : "user:" + auth.getName();
                allowed = allow("write:" + identity, 60);
                if (path.endsWith("/check-in")) allowed = allowed && allow("checkin:" + identity, 10);
            }
            if (!allowed) {
                res.setStatus(429); res.setHeader("Retry-After", "60"); res.setContentType("application/json;charset=UTF-8");
                res.getWriter().write("{\"message\":\"操作过于频繁，请一分钟后重试\"}"); return;
            }
        }
        chain.doFilter(req, res);
    }
}
