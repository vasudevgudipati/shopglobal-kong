package com.shopglobal.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Calls the marketplace API (mock API today, Kong API data plane later). Used by the storefront proxy
 * and by the offline stub assistant.
 */
@Component
public class ApiClient {

    public record Result(int status, String body, String contentType) {}

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;
    private final String base;
    private final String token;
    private final TokenProvider tokens;

    public ApiClient(ObjectMapper mapper, TokenProvider tokens,
                     @Value("${shopglobal.api.base-url}") String base,
                     @Value("${shopglobal.api.token:}") String token) {
        this.mapper = mapper;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        this.token = token;
        this.tokens = tokens;
    }

    private String bearer() {
        if (token != null && !token.isBlank()) return token;   // a fixed token wins if one is set
        return tokens.token();
    }

    /** Raw call used by the proxy controller. pathAndQuery starts with "/". */
    public Result call(String method, String pathAndQuery, String jsonBody) {
        return call(method, pathAndQuery, jsonBody, null);
    }

    /** customerId (may be null) is sent as X-Customer-Id so the API returns only that customer's orders and addresses. */
    public Result call(String method, String pathAndQuery, String jsonBody, String customerId) {
        Result r = send(method, pathAndQuery, jsonBody, customerId);
        if (r.status() == 401 && tokens.enabled()) {      // token expired or revoked: fetch a new one and retry once
            tokens.invalidate();
            r = send(method, pathAndQuery, jsonBody, customerId);
        }
        return r;
    }

    private Result send(String method, String pathAndQuery, String jsonBody, String customerId) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + pathAndQuery))
                    .timeout(Duration.ofSeconds(30))
                    .header("Accept", "application/json");
            String t = bearer();
            if (!t.isBlank()) b.header("Authorization", "Bearer " + t);
            if (customerId != null && !customerId.isBlank()) b.header("X-Customer-Id", customerId);
            if (jsonBody != null && !jsonBody.isEmpty()) {
                b.header("Content-Type", "application/json");
                b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            return new Result(r.statusCode(), r.body(), r.headers().firstValue("Content-Type").orElse("application/json"));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(502, "{\"error\":\"INTERRUPTED\",\"message\":\"Request interrupted\"}", "application/json");
        } catch (IOException e) {
            return new Result(502, "{\"error\":\"UPSTREAM_UNAVAILABLE\",\"message\":\"The shop service is not reachable\"}", "application/json");
        }
    }

    /** Parsed JSON call used by the stub assistant. Error bodies are returned as JSON too. */
    public JsonNode json(String method, String pathAndQuery, String jsonBody) {
        Result r = call(method, pathAndQuery, jsonBody);
        try {
            return mapper.readTree(r.body());
        } catch (IOException e) {
            return mapper.createObjectNode().put("error", "BAD_RESPONSE").put("message", "Unreadable response");
        }
    }
}
