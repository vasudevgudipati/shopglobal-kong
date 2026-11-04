package com.shopglobal.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The demo customers. There is no identity provider yet: a customer signs in with a user name and the shared demo
 * password (demo.password, default set in application.yml, override with DEMO_PASSWORD). The customer id is what the
 * marketplace API and the agent use to find that customer's orders and addresses.
 */
@Component
public class DemoUsers {

    public record User(String id, String username, String name) {}

    private static final List<User> USERS = List.of(
            new User("CUST-1001", "vasudev", "Vasudev Gudipati"),
            new User("CUST-1002", "tataji", "Tataji"),
            new User("CUST-1003", "shubham", "Shubham"),
            new User("CUST-1004", "kvamshi", "Kvamshi"));

    private final byte[] password;

    public DemoUsers(@Value("${demo.password}") String password) {
        this.password = password.getBytes(StandardCharsets.UTF_8);
    }

    public List<User> all() { return USERS; }

    public Optional<User> authenticate(String username, String pass) {
        if (username == null || pass == null) return Optional.empty();
        boolean passOk = MessageDigest.isEqual(password, pass.getBytes(StandardCharsets.UTF_8));   // constant-time compare
        Optional<User> u = USERS.stream().filter(x -> x.username().equals(username.trim().toLowerCase(Locale.ROOT))).findFirst();
        return passOk ? u : Optional.empty();
    }
}
