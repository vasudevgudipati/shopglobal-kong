package com.shopglobal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** A tiny stand-in for Keycloak + the Kong AI gateway (LLM + MCP), so the clients can be tested without the network. */
final class FakeGateway implements AutoCloseable {

    record Req(String path, Map<String, String> headers, String body) {
        String header(String n) { return headers.get(n.toLowerCase()); }
        JsonNode json() {
            try { return new ObjectMapper().readTree(body); } catch (IOException e) { throw new IllegalStateException(e); }
        }
        String rpcMethod() { return json().path("method").asText(""); }
    }

    record Reply(int status, String body, Map<String, String> headers) {
        static Reply json(int status, String body) { return new Reply(status, body, Map.of("Content-Type", "application/json")); }
        static Reply with(Reply r, String k, String v) {
            Map<String, String> h = new LinkedHashMap<>(r.headers);
            h.put(k, v);
            return new Reply(r.status, r.body, h);
        }
    }

    static final ObjectMapper MAPPER = new ObjectMapper();

    final List<Req> requests = Collections.synchronizedList(new ArrayList<>());
    /** Decides every answer. Tests replace it. */
    volatile Function<Req, Reply> handler = r -> Reply.json(404, "{}");
    private final HttpServer server;

    FakeGateway() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            Map<String, String> h = new LinkedHashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), String.join(",", v)));
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            Req req = new Req(ex.getRequestURI().getPath(), h, body);
            requests.add(req);
            Reply rep = handler.apply(req);
            rep.headers().forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            byte[] out = rep.body() == null ? new byte[0] : rep.body().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(rep.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    List<Req> to(String path) {
        synchronized (requests) { return requests.stream().filter(r -> r.path().equals(path)).toList(); }
    }

    @Override public void close() { server.stop(0); }

    // ---------------------------------------------------------------- canned answers

    static Reply token(String value, int expiresIn) {
        return Reply.json(200, "{\"access_token\":\"" + value + "\",\"expires_in\":" + expiresIn + "}");
    }

    static Reply chat(String content, String cacheStatus) {
        String body = "{\"model\":\"gpt-5-mini\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + quote(content)
                + "}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
        Reply r = Reply.json(200, body);
        return cacheStatus == null ? r : Reply.with(r, "X-Cache-Status", cacheStatus);
    }

    static Reply chatToolCall(String tool, String argsJson) {
        String body = "{\"model\":\"gpt-5-mini\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
                + "{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"" + tool + "\",\"arguments\":" + quote(argsJson) + "}}]}}]}";
        return Reply.json(200, body);
    }

    /** MCP answers for initialize / notifications / tools/list / tools/call; null for anything else. */
    static Reply mcp(Req r, String toolResultText) {
        switch (r.rpcMethod()) {
            case "initialize":
                return Reply.with(Reply.json(200, "{\"jsonrpc\":\"2.0\",\"id\":" + r.json().path("id").asInt() + ",\"result\":{}}"),
                        "Mcp-Session-Id", "S1");
            case "notifications/initialized":
                return new Reply(202, null, Map.of());
            case "tools/list":
                return Reply.json(200, "{\"jsonrpc\":\"2.0\",\"id\":" + r.json().path("id").asInt() + ",\"result\":{\"tools\":["
                        + "{\"name\":\"searchProducts\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}},"
                        + "{\"name\":\"cancelOrder\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]}}");
            case "tools/call":
                return Reply.json(200, "{\"jsonrpc\":\"2.0\",\"id\":" + r.json().path("id").asInt()
                        + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":" + quote(toolResultText) + "}]}}");
            default:
                return null;
        }
    }

    static String quote(String s) {
        try { return MAPPER.writeValueAsString(s); } catch (IOException e) { throw new IllegalStateException(e); }
    }
}
