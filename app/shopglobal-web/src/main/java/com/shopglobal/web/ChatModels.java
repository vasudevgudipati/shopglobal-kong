package com.shopglobal.web;

import java.util.List;

public final class ChatModels {
    private ChatModels() {}

    /** What the browser sees of a pending action. */
    public record PendingView(String id, String title, List<String> details) {}

    /** What happened behind a reply (intent, model, tools, tokens, cache). Only the UAT build shows it. */
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
        public ChatResponse withTrace(Trace t) { return new ChatResponse(reply, pending, blocked, status, t); }
        public static ChatResponse ok(String reply) { return new ChatResponse(reply, null, false, "ok"); }
    }

    public record MessageRequest(String sessionId, String message, String language) {}

    public record ConfirmRequest(String sessionId, String actionId, boolean approve, String language) {}

    public static class Session {
        public final String id;
        /** The signed-in customer this conversation belongs to. */
        public final DemoUsers.User owner;

        public Session(String id, DemoUsers.User owner) { this.id = id; this.owner = owner; }
    }
}
