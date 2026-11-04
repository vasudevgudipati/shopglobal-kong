package com.shopglobal.agent;

import com.shopglobal.agent.ChatModels.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HTTP face of the agent. The web app (or any other client) calls this; the agent in turn calls Kong
 * (AI data plane for the model, MCP for the tools). Conversation state is held here, keyed by sessionId.
 */
@RestController
@RequestMapping("/agent")
public class AgentController {

    private final KongAgent agent;
    private final String env;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public AgentController(KongAgent agent, @Value("${app.env:UAT}") String env) {
        this.agent = agent;
        this.env = env;
    }

    @GetMapping("/info")
    public Map<String, String> info() {
        return Map.of("service", "shopglobal-agent", "env", env, "mode", "kong");
    }

    @PostMapping("/message")
    public ChatResponse message(@RequestBody MessageRequest r) {
        String text = r.message() == null ? "" : r.message().trim();
        if (text.isEmpty()) return ChatResponse.ok("");
        Session s = session(r.sessionId());
        synchronized (s) {
            if (r.customerId() != null && !r.customerId().isBlank()) s.customerId = r.customerId();
            return agent.handle(s, text.length() > 2000 ? text.substring(0, 2000) : text, lang(r.language()));
        }
    }

    @PostMapping("/confirm")
    public ChatResponse confirm(@RequestBody ConfirmRequest r) {
        Session s = session(r.sessionId());
        synchronized (s) {
            if (r.customerId() != null && !r.customerId().isBlank()) s.customerId = r.customerId();
            return agent.confirm(s, r.actionId(), r.approve(), lang(r.language()));
        }
    }

    @PostMapping("/reset")
    public Map<String, String> reset(@RequestBody Map<String, String> body) {
        String id = body.get("sessionId");
        if (id != null) sessions.remove(id);
        return Map.of("status", "ok");
    }

    private Session session(String id) {
        String key = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;
        return sessions.computeIfAbsent(key, Session::new);
    }

    private static String lang(String l) {
        return "hi".equalsIgnoreCase(l) ? "hi" : "en";
    }
}
