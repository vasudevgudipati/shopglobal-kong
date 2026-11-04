package com.shopglobal.agent;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/** Shapes shared with the web app (same JSON). Keep in step with shopglobal-web ChatModels. */
public final class ChatModels {
    private ChatModels() {}

    /** A write action the model asked for. It is held until the customer presses Confirm. */
    public record PendingAction(String id, String tool, String argsJson, String title, List<String> details) {}

    public record PendingView(String id, String title, List<String> details) {}

    /** What happened behind the reply. The web app shows it only in the UAT build. */
    public record Trace(String intent, String tier, String model, List<String> tools, int promptTokens,
                        int completionTokens, List<String> cache, long millis) {}

    /**
     * @param blocked true when Kong refused the request (prompt guard, token quota) - the reply is then Kong's standard message
     * @param status  ok | blocked | quota | unavailable
     */
    public record ChatResponse(String reply, PendingView pending, boolean blocked, String status, Trace trace) {
        public ChatResponse(String reply, PendingView pending, boolean blocked, String status) {
            this(reply, pending, blocked, status, null);
        }
        public static ChatResponse ok(String reply) { return new ChatResponse(reply, null, false, "ok"); }
        public static ChatResponse ask(String reply, PendingAction p) {
            return new ChatResponse(reply, new PendingView(p.id(), p.title(), p.details()), false, "ok");
        }
        public ChatResponse withTrace(Trace t) { return new ChatResponse(reply, pending, blocked, status, t); }
    }

    /** customerId is optional: the web app has no login yet, so the agent then uses agent.default-customer-id. */
    public record MessageRequest(String sessionId, String message, String language, String customerId) {}

    public record ConfirmRequest(String sessionId, String actionId, boolean approve, String language, String customerId) {}

    public static class Session {
        public final String id;
        /** OpenAI-format messages (system message first). */
        public final List<ObjectNode> messages = new ArrayList<>();
        public PendingAction pending;
        public String lastIntent;
        /** Whose data the tools may touch; sent to Kong as X-Customer-Id on every MCP request. */
        public String customerId;

        public Session(String id) { this.id = id; }
    }
}
