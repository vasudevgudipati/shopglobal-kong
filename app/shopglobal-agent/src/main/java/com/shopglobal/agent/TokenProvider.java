package com.shopglobal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.LongSupplier;

/**
 * OAuth 2.0 client-credentials token for every call to the Kong AI gateway (LLM and MCP). Keycloak issues it.
 *
 *  - fetched on first use and cached;
 *  - renewed automatically {@code oauth.skew-seconds} before it expires;
 *  - {@link #refresh()} forces a new one (used after the gateway answers 401);
 *  - a failure to get a token is an exception, never an empty token silently sent to the gateway;
 *  - the token and the client secret are never logged.
 *
 * Disabled (returns "") only when {@code oauth.token-url} is empty. The client secret comes from an environment
 * variable or Kubernetes Secret, never from a file in Git.
 */
@Component
public class TokenProvider {

    private static final Logger log = LoggerFactory.getLogger(TokenProvider.class);

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;
    private final String tokenUrl, clientId, clientSecret, scope;
    private final long skewMillis;

    private LongSupplier clock = System::currentTimeMillis;   // replaced in tests
    private String cached;
    private long expiresAtMillis;

    public TokenProvider(ObjectMapper mapper,
                         @Value("${oauth.token-url:}") String tokenUrl,
                         @Value("${oauth.client-id:}") String clientId,
                         @Value("${oauth.client-secret:}") String clientSecret,
                         @Value("${oauth.scope:}") String scope,
                         @Value("${oauth.skew-seconds:30}") long skewSeconds) {
        this.mapper = mapper;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
        this.skewMillis = Math.max(0, skewSeconds) * 1000;
    }

    public boolean enabled() { return tokenUrl != null && !tokenUrl.isBlank(); }

    /** A valid access token ("" when OAuth is not configured). Throws KongException(503) when none can be obtained. */
    public synchronized String token() {
        if (!enabled()) return "";
        if (cached != null && clock.getAsLong() < expiresAtMillis - skewMillis) return cached;
        return fetch();
    }

    /** Throw the cached token away and get a new one now. */
    public synchronized String refresh() {
        cached = null;
        return enabled() ? fetch() : "";
    }

    /** Forget the cached token; the next {@link #token()} fetches a new one. */
    public synchronized void invalidate() { cached = null; }

    // package-private: lets tests move time forward
    synchronized void useClock(LongSupplier c) { this.clock = c; }

    private String fetch() {
        String form = "grant_type=client_credentials&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret)
                + (scope == null || scope.isBlank() ? "" : "&scope=" + enc(scope));
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(tokenUrl)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .header("ngrok-skip-browser-warning", "true")   // harmless elsewhere; avoids the ngrok interstitial page
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                cached = null;
                log.error("Token request to the identity provider failed: HTTP {} (check client id / secret / scope)", r.statusCode());
                throw new KongException(503, "token endpoint returned HTTP " + r.statusCode());
            }
            JsonNode n = mapper.readTree(r.body());
            String t = n.path("access_token").asText("");
            if (t.isEmpty()) {
                cached = null;
                log.error("Token response from the identity provider had no access_token");
                throw new KongException(503, "token endpoint returned no access_token");
            }
            cached = t;
            expiresAtMillis = clock.getAsLong() + n.path("expires_in").asLong(60) * 1000;
            log.debug("Obtained a new access token, valid for {} s", n.path("expires_in").asLong(60));
            return cached;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KongException(503, "interrupted while getting a token");
        } catch (IOException e) {
            cached = null;
            log.error("Token endpoint not reachable: {}", e.getClass().getSimpleName());
            throw new KongException(503, "token endpoint not reachable");
        }
    }

    private static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
}
