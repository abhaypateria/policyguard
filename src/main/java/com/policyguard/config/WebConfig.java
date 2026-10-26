package com.policyguard.config;

import com.policyguard.exception.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Guards for a PUBLIC deployment:
 *  - CORS: which websites (our Vercel frontend) may call this API from a browser
 *  - Rate limit: per-IP requests/minute + a global daily cap, so nobody can burn the LLM quota
 *  - Admin key: uploads, deletes and evaluation runs need the X-Admin-Key header (when configured)
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PolicyGuardProperties props;

    public WebConfig(PolicyGuardProperties props) {
        this.props = props;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        String[] origins = props.security().corsAllowedOrigins().split("\\s*,\\s*");
        registry.addMapping("/**")
                .allowedOriginPatterns(origins)
                .allowedMethods("GET", "POST", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimiter(props.security().chatRequestsPerMinute(), props.security().chatRequestsPerDay()))
                .addPathPatterns("/api/chat/**");
        registry.addInterceptor(new AdminKeyGuard(props.security().adminKey()))
                .addPathPatterns("/api/documents/**", "/api/eval/**");
    }

    /** Sliding one-minute window per client IP, plus a global daily ceiling. */
    static final class RateLimiter implements HandlerInterceptor {
        private final int perMinute;
        private final int perDay;
        private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();
        private final AtomicInteger today = new AtomicInteger();
        private volatile LocalDate day = LocalDate.now();

        RateLimiter(int perMinute, int perDay) {
            this.perMinute = perMinute;
            this.perDay = perDay;
        }

        @Override
        public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
            if (!"POST".equalsIgnoreCase(req.getMethod())) return true;
            if (!LocalDate.now().equals(day)) {
                day = LocalDate.now();
                today.set(0);
            }
            if (today.incrementAndGet() > perDay) {
                throw ApiException.tooManyRequests("Daily question limit reached for this demo deployment - try again tomorrow");
            }
            if (hits.size() > 10_000) hits.clear();
            long now = System.currentTimeMillis();
            Deque<Long> window = hits.computeIfAbsent(clientIp(req), k -> new ArrayDeque<>());
            synchronized (window) {
                while (!window.isEmpty() && now - window.peekFirst() > 60_000) window.pollFirst();
                if (window.size() >= perMinute) {
                    throw ApiException.tooManyRequests("Too many questions in one minute - please wait a moment");
                }
                window.addLast(now);
            }
            return true;
        }

        private static String clientIp(HttpServletRequest req) {
            String forwarded = req.getHeader("X-Forwarded-For");
            return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : req.getRemoteAddr();
        }
    }

    /** Write operations need the admin key. Reads stay public. Constant-time comparison. */
    static final class AdminKeyGuard implements HandlerInterceptor {
        private final byte[] key;

        AdminKeyGuard(String key) {
            this.key = key == null ? new byte[0] : key.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
            String method = req.getMethod();
            if (key.length == 0 || "GET".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) return true;
            String supplied = req.getHeader("X-Admin-Key");
            if (supplied == null || !MessageDigest.isEqual(key, supplied.getBytes(StandardCharsets.UTF_8))) {
                throw ApiException.unauthorized("Admin key required for this operation (X-Admin-Key header)");
            }
            return true;
        }
    }
}
