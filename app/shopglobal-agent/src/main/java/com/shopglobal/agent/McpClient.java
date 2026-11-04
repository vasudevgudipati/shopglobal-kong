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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Minimal MCP client (streamable HTTP, JSON-RPC 2.0) for the tools Kong exposes on the AI gateway.
 * Kong converts the published marketplace APIs to MCP tools, so no MCP server is built or run here.
 *
 * Every request (initialize, tools/list, every tools/call) carries the bearer token and "X-Customer-Id", so the
 * order and address tools act only on that customer's data. The model never supplies a customer id.
 * Arguments and results are never logged (they contain addresses and phone numbers).
 */
@Component
public class McpClient {

    private static final Logger log = LoggerFactory.getLogger(McpClient.class);
    private static final String PROTOCOL = "2025-03-26";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");

    private final ObjectMapper mapper;
    private final KongHttp kong;
    private final String url;
    private final String defaultCustomerId;
    private final AtomicInteger ids = new AtomicInteger(1);

    private volatile String sessionId;
    private volatile ArrayNode openAiTools;

    public McpClient(ObjectMapper mapper, KongHttp kong,
                     @Value("${kong.ai.base-url}") String baseUrl,
                     @Value("${kong.mcp.path:/mcp/marketplace}") String mcpPath,
                     @Value("${agent.default-customer-id:CUST-1001}") String defaultCustomerId) {
        this.mapper = mapper;
        this.kong = kong;
        this.url = LlmClient.join(baseUrl, mcpPath);
        this.defaultCustomerId = defaultCustomerId;
    }

    /** The id to send, or the default customer when none (or an unsafe one) was given. */
    String customer(String customerId) {
        return customerId != null && SAFE_ID.matcher(customerId).matches() ? customerId : defaultCustomerId;
    }

    /** Tool list in the OpenAI "tools" format, loaded once and cached. */
    public synchronized ArrayNode tools() {
        if (openAiTools != null) return openAiTools;
        String who = customer(null);
        initialize(who);
        JsonNode result = rpc("tools/list", mapper.createObjectNode(), who, true);
        ArrayNode out = mapper.createArrayNode();
        for (JsonNode t : result.path("tools")) {
            ObjectNode fn = mapper.createObjectNode();
            fn.put("name", t.path("name").asText());
            fn.put("description", t.path("description").asText(""));
            JsonNode schema = t.path("inputSchema");
            fn.set("parameters", schema.isObject() ? schema : mapper.createObjectNode().put("type", "object"));
            ObjectNode wrapper = mapper.createObjectNode();
            wrapper.put("type", "function");
            wrapper.set("function", fn);
            out.add(wrapper);
        }
        openAiTools = out;
        log.info("Loaded {} MCP tools", out.size());
        return out;
    }

    /** Runs one tool for this customer and returns its text output. Failures come back as text starting with ERROR. */
    public String call(String name, JsonNode arguments, String customerId) {
        String who = customer(customerId);
        try {
            tools(); // make sure we are initialised
            ObjectNode params = mapper.createObjectNode();
            params.put("name", name);
            params.set("arguments", arguments == null || arguments.isNull() ? mapper.createObjectNode() : arguments);
            JsonNode result;
            try {
                result = rpc("tools/call", params, who, false);
            } catch (KongException e) {
                if (e.status() != 404) throw e;
                // the MCP session expired (Kong restarted): start a new one and run the call again
                log.info("MCP session not found, starting a new one");
                reset();
                tools();
                result = rpc("tools/call", params, who, false);
            }
            StringBuilder sb = new StringBuilder();
            for (JsonNode c : result.path("content")) {
                if ("text".equals(c.path("type").asText())) sb.append(c.path("text").asText());
            }
            String text = sb.length() == 0 ? result.toString() : sb.toString();
            boolean err = result.path("isError").asBoolean(false);
            log.info("MCP tool {} finished{}", name, err ? " with an error" : "");
            return err ? "ERROR: " + text : text;
        } catch (KongException e) {
            log.warn("MCP tool {} failed: HTTP {}", name, e.status());
            return "ERROR: the tool could not be run (HTTP " + e.status() + ")";
        } catch (RuntimeException e) {
            log.warn("MCP tool {} failed: {}", name, e.getClass().getSimpleName());
            return "ERROR: the tool could not be run";
        }
    }

    /** Forget the cached session/tools, e.g. after Kong restarts or the tool list changes. */
    public synchronized void reset() {
        sessionId = null;
        openAiTools = null;
    }

    // ------------------------------------------------------------------ JSON-RPC plumbing

    private void initialize(String who) {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "shopglobal-agent").put("version", "1.0.0");
        rpc("initialize", params, who, true);
        ObjectNode note = mapper.createObjectNode();
        note.put("jsonrpc", "2.0");
        note.put("method", "notifications/initialized");
        post(note.toString(), who, true);
    }

    private JsonNode rpc(String method, JsonNode params, String who, boolean safeToRepeat) {
        int id = ids.getAndIncrement();
        ObjectNode req = mapper.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", id);
        req.put("method", method);
        req.set("params", params);
        KongHttp.Response r = post(req.toString(), who, safeToRepeat);
        JsonNode msg = pickResponse(r, id);
        if (msg.has("error")) {
            throw new KongException(502, "MCP error " + msg.path("error").path("code").asText("?"));
        }
        return msg.path("result");
    }

    /** @param safeToRepeat false for tools/call: a tool that changes data is never re-sent after a timeout or 5xx */
    private KongHttp.Response post(String json, String who, boolean safeToRepeat) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "application/json, text/event-stream");
        h.put("MCP-Protocol-Version", PROTOCOL);
        h.put("X-Customer-Id", who);
        String sid = sessionId;
        if (sid != null) h.put("Mcp-Session-Id", sid);
        KongHttp.Response r = kong.post(url, h, json, safeToRepeat);
        String newSid = r.header("Mcp-Session-Id");
        if (newSid != null) sessionId = newSid;
        return r;
    }

    /** The reply is either plain JSON or an SSE stream whose "data:" lines carry JSON-RPC messages. */
    private JsonNode pickResponse(KongHttp.Response r, int id) {
        String ct = r.header("Content-Type") == null ? "" : r.header("Content-Type");
        String body = r.body() == null ? "" : r.body().trim();
        try {
            if (!ct.contains("text/event-stream")) return mapper.readTree(body);
            JsonNode first = null;
            for (String line : body.split("\\r?\\n")) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty()) continue;
                JsonNode n = mapper.readTree(data);
                if (n.path("id").asInt(-1) == id) return n;
                if (first == null && (n.has("result") || n.has("error"))) first = n;
            }
            if (first != null) return first;
        } catch (IOException e) {
            throw new KongException(502, "Unreadable MCP response");
        }
        throw new KongException(502, "Empty MCP response");
    }
}
