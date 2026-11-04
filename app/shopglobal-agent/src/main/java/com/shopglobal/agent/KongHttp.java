package com.shopglobal.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * The one place that talks HTTP to the Kong AI gateway (used by the LLM client and the MCP client).
 *
 * Every request carries "Authorization: Bearer &lt;token&gt;". The retry rules are:
 *  - 401: get a new token and send the request again, once;
 *  - 429: wait for Retry-After (or a short default) and send again, once;
 *  - 5xx or timeout/connection error: send again once, but only when the call is safe to repeat;
 *  - anything else that is not 2xx: KongException straight away.
 * Each rule is used at most once per call. Request and response bodies are never logged.
 * No Cache-Control header is added: the gateway's semantic cache skips requests that carry "no-cache".
 */
@Component
public class KongHttp {

    private static final Logger log = LoggerFactory.getLogger(KongHttp.class);

    public record Response(int status, String body, HttpHeaders headers) {
        public String header(String name) { return headers.firstValue(name).orElse(null); }
    }

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final TokenProvider tokens;
    private final Duration timeout;
    private final long serverRetryDelayMs;
    private final long defaultRateLimitDelayMs;
    private final long maxRetryAfterMs;

    public KongHttp(TokenProvider tokens,
                    @Value("${kong.http.timeout-seconds:60}") int timeoutSeconds,
                    @Value("${kong.http.retry-delay-ms:500}") long serverRetryDelayMs,
                    @Value("${kong.http.rate-limit-delay-ms:2000}") long defaultRateLimitDelayMs,
                    @Value("${kong.http.max-retry-after-seconds:10}") long maxRetryAfterSeconds) {
        this.tokens = tokens;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.serverRetryDelayMs = serverRetryDelayMs;
        this.defaultRateLimitDelayMs = defaultRateLimitDelayMs;
        this.maxRetryAfterMs = maxRetryAfterSeconds * 1000;
    }

    /**
     * @param safeToRepeat false for calls that change data (a tool call): they are then never re-sent after a
     *                     timeout or a 5xx, because the first attempt may already have been carried out
     */
    public Response post(String url, Map<String, String> headers, String body, boolean safeToRepeat) {
        boolean refreshed = false, rateRetried = false, serverRetried = false;
        while (true) {
            Response r;
            try {
                r = send(url, headers, body);
            } catch (IOException e) {   // includes timeouts
                if (safeToRepeat && !serverRetried) {
                    serverRetried = true;
                    log.warn("Gateway call failed ({}), retrying once", e.getClass().getSimpleName());
                    pause(serverRetryDelayMs);
                    continue;
                }
                throw new KongException(503, "gateway not reachable: " + e.getClass().getSimpleName());
            }
            int st = r.status();
            if (st / 100 == 2) return r;
            if (st == 401 && tokens.enabled() && !refreshed) {
                refreshed = true;
                log.info("Gateway answered 401, refreshing the access token and retrying once");
                tokens.refresh();
                continue;
            }
            if (st == 429 && !rateRetried) {
                rateRetried = true;
                long wait = retryAfterMs(r);
                log.info("Gateway answered 429, retrying once after {} ms", wait);
                pause(wait);
                continue;
            }
            if (st >= 500 && safeToRepeat && !serverRetried) {
                serverRetried = true;
                log.warn("Gateway answered {}, retrying once", st);
                pause(serverRetryDelayMs);
                continue;
            }
            if (st / 100 == 4 && st != 401 && st != 429) {
                // Gateway / provider errors describe what is wrong with the request (format, guard rule). Only the start of the
                // text is logged, with digits masked, so that no phone numbers or ids end up in the log.
                log.warn("Gateway rejected the request with HTTP {}: {}", st, safeSnippet(r.body()));
            }
            if (st == 401) log.error("Gateway still answers 401 after refreshing the token (check the Kong auth plugin / Keycloak client)");
            throw new KongException(st, r.body());
        }
    }

    private Response send(String url, Map<String, String> headers, String body) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
        headers.forEach(b::header);
        String bearer = tokens.token();   // throws KongException(503) if a token cannot be obtained
        if (!bearer.isEmpty()) b.header("Authorization", "Bearer " + bearer);
        try {
            HttpResponse<String> r = http.send(b.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return new Response(r.statusCode(), r.body(), r.headers());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KongException(503, "interrupted");
        }
    }

    static String safeSnippet(String body) {
        if (body == null) return "";
        String t = body.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\d{4,}", "####");
        return t.length() > 300 ? t.substring(0, 300) + "..." : t;
    }

    private long retryAfterMs(Response r) {
        String v = r.header("Retry-After");
        if (v != null) {
            try {
                return Math.min(maxRetryAfterMs, Math.max(0, Long.parseLong(v.trim()) * 1000));
            } catch (NumberFormatException ignored) {
                // HTTP-date form is not used by Kong; fall through to the default
            }
        }
        return Math.min(maxRetryAfterMs, defaultRateLimitDelayMs);
    }

    private static void pause(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
