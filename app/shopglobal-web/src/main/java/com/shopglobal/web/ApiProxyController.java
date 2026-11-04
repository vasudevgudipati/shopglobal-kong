package com.shopglobal.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * The browser only ever talks to this app. Storefront data calls (/api/**) are passed through to the
 * marketplace API, so the upstream can move behind Kong without touching the front end.
 *
 * Browsing (reading products, categories, return policies) is public. Everything about a customer - orders,
 * saved addresses, placing an order - needs a signed-in customer; the customer id then comes from the server
 * session and is sent upstream as X-Customer-Id. A customer id sent by the browser is never used.
 */
@RestController
public class ApiProxyController {

    private final ApiClient api;

    public ApiProxyController(ApiClient api) {
        this.api = api;
    }

    @RequestMapping("/api/**")
    public ResponseEntity<String> proxy(HttpServletRequest req, HttpSession session, @RequestBody(required = false) byte[] body) {
        String uri = req.getRequestURI();
        boolean personal = uri.startsWith("/api/orders") || uri.startsWith("/api/addresses") || !"GET".equals(req.getMethod());
        DemoUsers.User user = AuthController.current(session);
        if (personal && user == null) {
            return ResponseEntity.status(401).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"error\":\"UNAUTHENTICATED\",\"message\":\"Please sign in\"}");
        }
        String path = uri + (req.getQueryString() != null ? "?" + req.getQueryString() : "");
        String json = body == null ? null : new String(body, StandardCharsets.UTF_8);
        ApiClient.Result r = api.call(req.getMethod(), path, json, user == null ? null : user.id());
        return ResponseEntity.status(HttpStatusCode.valueOf(r.status()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(r.body());
    }
}
