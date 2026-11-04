package com.shopglobal.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Demo sign-in. The signed-in customer is kept in the server session; the browser never sends a customer id. */
@RestController
@RequestMapping("/auth")
public class AuthController {

    static final String SESSION_USER = "sg.user";

    public record LoginRequest(String username, String password) {}

    private final DemoUsers users;

    public AuthController(DemoUsers users) {
        this.users = users;
    }

    /** The customer of this request, or null. */
    static DemoUsers.User current(HttpSession session) {
        Object u = session == null ? null : session.getAttribute(SESSION_USER);
        return u instanceof DemoUsers.User ? (DemoUsers.User) u : null;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest r, HttpServletRequest req) throws InterruptedException {
        var u = users.authenticate(r.username(), r.password());
        if (u.isEmpty()) {
            Thread.sleep(400);   // slows down password guessing
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("error", "INVALID_LOGIN", "message", "Wrong user name or password"));
        }
        HttpSession old = req.getSession(false);
        if (old != null) old.invalidate();              // new session id on every sign-in
        req.getSession(true).setAttribute(SESSION_USER, u.get());
        return ResponseEntity.ok(view(u.get()));
    }

    @GetMapping("/me")
    public Map<String, Object> me(HttpSession session) {
        DemoUsers.User u = current(session);
        return u == null ? Map.of("signedIn", false) : view(u);
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpSession session) {
        if (session != null) session.invalidate();
        return Map.of("signedIn", false);
    }

    private static Map<String, Object> view(DemoUsers.User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("signedIn", true);
        m.put("username", u.username());
        m.put("name", u.name());
        return m;
    }
}
