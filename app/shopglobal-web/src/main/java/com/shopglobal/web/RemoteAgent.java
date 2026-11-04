package com.shopglobal.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopglobal.web.ChatModels.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * The website talks to the separate shopglobal-agent service (which in turn talks to Kong). The conversation
 * state lives in the agent service, keyed by the chat session id.
 */
@Component
public class RemoteAgent implements Agent {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper mapper;
    private final String base;

    public RemoteAgent(ObjectMapper mapper, @Value("${agent.remote.url:http://localhost:8082}") String base) {
        this.mapper = mapper;
        this.base = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    @Override
    public ChatResponse handle(Session s, String userText, String language) {
        return post("/agent/message", Map.of("sessionId", s.id, "message", userText, "language", language, "customerId", s.owner.id()), language);
    }

    @Override
    public ChatResponse confirm(Session s, String actionId, boolean approve, String language) {
        return post("/agent/confirm", Map.of("sessionId", s.id, "actionId", actionId == null ? "" : actionId,
                "approve", approve, "language", language, "customerId", s.owner.id()), language);
    }

    private ChatResponse post(String path, Map<String, Object> body, String language) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) return unavailable(language);
            return mapper.readValue(r.body(), ChatResponse.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable(language);
        } catch (Exception e) {
            return unavailable(language);
        }
    }

    private static ChatResponse unavailable(String language) {
        return new ChatResponse("hi".equals(language) ? "सहायक अभी उपलब्ध नहीं है। कृपया थोड़ी देर बाद कोशिश करें।"
                : "The assistant is not available right now. Please try again in a moment.", null, false, "unavailable");
    }
}
