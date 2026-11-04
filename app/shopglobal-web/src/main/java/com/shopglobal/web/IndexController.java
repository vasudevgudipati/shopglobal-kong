package com.shopglobal.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Serves index.html with the labels of the current build (UAT / PROD) filled in. */
@Controller
public class IndexController {

    private final String html;

    public IndexController(AppEnv env) throws Exception {
        String raw;
        try (InputStream in = new ClassPathResource("static/index.html").getInputStream()) {
            raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        html = raw.replace("{{TITLE}}", env.title())
                .replace("{{ENV_LC}}", env.name().toLowerCase())
                .replace("{{ENV}}", env.name())
                .replace("{{BANNER}}", env.banner())
                .replace("{{TOPBAR}}", env.topbar())
                .replace("{{PILL}}", env.pill())
                .replace("{{FOOTER}}", env.footer());
    }

    @GetMapping(value = {"/", "/index.html"}, produces = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8")
    public ResponseEntity<String> index() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(html);
    }
}
