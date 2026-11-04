package com.shopglobal.web;

import com.shopglobal.web.ChatModels.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The chat needs a signed-in customer: the conversation belongs to that customer and the agent acts on that customer's data. */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private final Agent agent;
    private final AppEnv env;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public ChatController(Agent agent, AppEnv env) {
        this.agent = agent;
        this.env = env;
    }

    @GetMapping("/info")
    public Map<String, String> info() {
        return Map.of("env", env.name(), "showTrace", String.valueOf(env.showTrace()), "title", env.chatTitle());
    }

    @PostMapping("/message")
    public ChatResponse message(@RequestBody MessageRequest r, HttpSession http) {
        DemoUsers.User user = user(http);
        String text = r.message() == null ? "" : r.message().trim();
        if (text.isEmpty()) return ChatResponse.ok("");
        Session s = session(r.sessionId(), user);
        synchronized (s) {
            return clean(agent.handle(s, text.length() > 2000 ? text.substring(0, 2000) : text, lang(r.language())));
        }
    }

    @PostMapping("/confirm")
    public ChatResponse confirm(@RequestBody ConfirmRequest r, HttpSession http) {
        DemoUsers.User user = user(http);
        Session s = session(r.sessionId(), user);
        synchronized (s) {
            return clean(agent.confirm(s, r.actionId(), r.approve(), lang(r.language())));
        }
    }

    @PostMapping("/reset")
    public Map<String, String> reset(@RequestBody Map<String, String> body, HttpSession http) {
        DemoUsers.User user = AuthController.current(http);
        String id = body.get("sessionId");
        if (user != null && id != null) sessions.remove(key(id, user));
        return Map.of("status", "ok");
    }

    private static DemoUsers.User user(HttpSession http) {
        DemoUsers.User u = AuthController.current(http);
        if (u == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Please sign in");
        return u;
    }

    /** The debug trace is removed on the server in PROD, so it can never reach a production browser. */
    private ChatResponse clean(ChatResponse r) {
        return env.showTrace() ? r : r.withTrace(null);
    }

    /** One conversation per customer and browser session: another customer can never read or continue it. */
    private static String key(String id, DemoUsers.User user) {
        return user.username() + "|" + ((id == null || id.isBlank()) ? UUID.randomUUID().toString() : id);
    }

    private Session session(String id, DemoUsers.User user) {
        return sessions.computeIfAbsent(key(id, user), k -> new Session(k, user));
    }

    private static String lang(String l) {
        return "hi".equalsIgnoreCase(l) ? "hi" : "en";
    }
}
