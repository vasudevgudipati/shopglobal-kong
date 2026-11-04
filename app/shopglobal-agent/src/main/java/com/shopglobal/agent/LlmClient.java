package com.shopglobal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible chat call to the Kong AI gateway. The agent never names a provider and never picks a
 * model tier: it always asks for "shopglobal-auto" and Kong routes by meaning, applies the prompt guard,
 * the semantic cache and the quotas. Non-streaming (no "stream" field is sent).
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);

    /** One model answer plus what Kong told us about it (model actually used, token usage, cache status). */
    public record LlmResult(JsonNode message, String model, int promptTokens, int completionTokens, String cache) {}

    private final ObjectMapper mapper;
    private final KongHttp kong;
    private final String url;
    private final String model;
    private final String env;
    private final String temperature;

    public LlmClient(ObjectMapper mapper, KongHttp kong,
                     @Value("${kong.ai.base-url}") String baseUrl,
                     @Value("${kong.ai.chat-path:/v1/chat/completions}") String chatPath,
                     @Value("${agent.model:shopglobal-auto}") String model,
                     @Value("${app.env:UAT}") String env,
                     @Value("${agent.temperature:}") String temperature) {
        this.mapper = mapper;
        this.kong = kong;
        this.url = join(baseUrl, chatPath);
        this.model = model;
        this.env = env;
        this.temperature = temperature;
    }

    static String join(String base, String path) {
        String b = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        return b + (path.startsWith("/") ? path : "/" + path);
    }

    /**
     * The last message in {@code messages} must be the customer's plain question (or a tool result); the system
     * message, which carries the reply language and all context, is built by the caller.
     * Throws KongException on a non-2xx answer after the retry rules in {@link KongHttp}.
     */
    public LlmResult chat(List<ObjectNode> messages, ArrayNode tools, String language) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        // Not sent by default: some models (for example OpenAI's GPT-5 family) reject any value except their default.
        if (temperature != null && !temperature.isBlank()) body.put("temperature", Double.parseDouble(temperature));
        ArrayNode msgs = body.putArray("messages");
        for (ObjectNode m : messages) msgs.add(m);
        if (tools != null && tools.size() > 0) {
            body.set("tools", tools);
            body.put("tool_choice", "auto");
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("X-Customer-Language", language);
        headers.put("X-App-Env", env);

        long t0 = System.currentTimeMillis();
        KongHttp.Response r;
        try {
            r = kong.post(url, headers, mapper.writeValueAsString(body), true);
        } catch (IOException e) {
            throw new KongException(500, "could not build the request");
        }
        String cache = r.header("X-Cache-Status");
        log.debug("LLM response X-Cache-Status={}", cache == null ? "-" : cache);
        try {
            JsonNode root = mapper.readTree(r.body());
            JsonNode msg = root.path("choices").path(0).path("message");
            if (msg.isMissingNode() || msg.isNull()) throw new KongException(502, "no message in the model response");
            LlmResult res = new LlmResult(msg, root.path("model").asText("?"), root.path("usage").path("prompt_tokens").asInt(0),
                    root.path("usage").path("completion_tokens").asInt(0), cache == null ? "-" : cache);
            log.info("LLM call ok: model={} cache={} promptTokens={} completionTokens={} ms={}", res.model(), res.cache(),
                    res.promptTokens(), res.completionTokens(), System.currentTimeMillis() - t0);
            return res;
        } catch (IOException e) {
            throw new KongException(502, "unreadable model response");
        }
    }
}
