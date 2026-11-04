package com.shopglobal.mockapi;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Demo housekeeping. Deliberately outside /api and hidden from the OpenAPI spec, so it is never published
 * through Kong or turned into an MCP tool. Call it directly on the pod / NodePort between demo runs.
 */
@Hidden
@RestController
@RequestMapping("/internal")
public class AdminController {

    private final DbInitializer init;

    public AdminController(DbInitializer init) {
        this.init = init;
    }

    @PostMapping("/reset-demo")
    public Map<String, String> reset() throws Exception {
        init.resetDemoOrders();
        return Map.of("status", "ok", "message", "Orders reset to the 4 seed orders");
    }
}
