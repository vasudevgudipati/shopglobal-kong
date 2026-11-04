package com.shopglobal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * OAuth 2.0 client-credentials token for calls to Kong (Keycloak issues it). The token is fetched on first use,
 * cached until shortly before it expires, and refreshed automatically. Disabled when oauth.token-url is empty.
 * The client secret comes from an environment variable / Kubernetes Secret, never from a file in Git.
 */
@Component
public class TokenProvider {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;
    private final String tokenUrl, clientId, clientSecret, scope;

    private String cached;
    private long expiresAtMillis;

    public TokenProvider(ObjectMapper mapper,
                         @Value("${oauth.token-url:}") String tokenUrl,
                         @Value("${oauth.client-id:}") String clientId,
                         @Value("${oauth.client-secret:}") String clientSecret,
                         @Value("${oauth.scope:}") String scope) {
        this.mapper = mapper;
        this.tokenUrl = tokenUrl;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.scope = scope;
    }

    public boolean enabled() { return tokenUrl != null && !tokenUrl.isBlank(); }

    /** A valid access token, or "" when OAuth is not configured or the token endpoint cannot be reached. */
    public synchronized String token() {
        if (!enabled()) return "";
        if (cached != null && System.currentTimeMillis() < expiresAtMillis - 30_000) return cached;
        try {
            String form = "grant_type=client_credentials&client_id=" + enc(clientId) + "&client_secret=" + enc(clientSecret)
                    + (scope == null || scope.isBlank() ? "" : "&scope=" + enc(scope));
            HttpRequest req = HttpRequest.newBuilder(URI.create(tokenUrl)).timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .header("ngrok-skip-browser-warning", "true")   // harmless elsewhere; avoids the ngrok interstitial page
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) { cached = null; return ""; }
            JsonNode n = mapper.readTree(r.body());
            cached = n.path("access_token").asText(null);
            expiresAtMillis = System.currentTimeMillis() + n.path("expires_in").asLong(60) * 1000;
            return cached == null ? "" : cached;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } catch (Exception e) {
            cached = null;
            return "";
        }
    }

    /** Forget the cached token (call after a 401 so the next call fetches a new one). */
    public synchronized void invalidate() { cached = null; }

    private static String enc(String s) { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8); }
}
