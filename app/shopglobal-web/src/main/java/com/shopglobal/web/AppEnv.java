package com.shopglobal.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Which build this is. One code base, two builds: UAT (test, debug trace on, test banner, violet theme) and
 * PROD (clean storefront, navy/orange theme, no debug). The value comes from the Maven profile (-Puat / -Pprod)
 * and can be overridden at run time with APP_ENV.
 */
@Component
public class AppEnv {

    private final String name;
    private final String version;

    public AppEnv(@Value("${app.env:UAT}") String name, @Value("${app.version:1.0.0}") String version) {
        this.name = "PROD".equalsIgnoreCase(name) ? "PROD" : "UAT";
        this.version = version;
    }

    public String name() { return name; }
    public boolean prod() { return "PROD".equals(name); }
    public boolean showTrace() { return !prod(); }
    public String chatTitle() { return prod() ? "ShopGlobal Assistant" : "ShopGlobal Assistant (UAT)"; }

    public String title() {
        return prod() ? "ShopGlobal – Everything you love, delivered" : "[UAT] ShopGlobal – test build";
    }
    public String topbar() {
        return prod() ? "🚚 Free delivery on orders above ₹999" : "🧪 UAT build – test data only. No real orders or payments.";
    }
    public String banner() {
        return prod() ? "" : "<div class=\"env-banner\">UAT · TEST ENVIRONMENT · v" + version
                + " · chat debug trace is ON</div>";
    }
    public String pill() { return prod() ? "" : "<span class=\"env-pill\">UAT</span>"; }
    public String footer() {
        return prod() ? "© 2026 ShopGlobal Marketplace. A demo storefront."
                : "UAT build v" + version + " · Konnect control plane: shopglobal-uat · data is reset between demos";
    }
}
