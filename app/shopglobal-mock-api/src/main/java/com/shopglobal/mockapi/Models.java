package com.shopglobal.mockapi;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** All data shapes of the mock APIs in one place. */
public final class Models {
    private Models() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Product(String id, String name, String brand, String category, String subCategory,
                          int price, int mrp, double rating, int ratingCount,
                          List<String> colors, List<String> sizes, String sizeType, String fit,
                          String description, List<String> highlights, int stock, String tag) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReturnPolicy(int days, String condition, String refund, boolean exchange) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Address {
        public String name;
        public String line1;
        public String line2;
        public String city;
        public String state;
        public String pincode;
        public String phone;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OrderItem {
        @JsonIgnore
        public int lineNo;
        public String productId;
        public String name;
        public String brand;
        public int qty;
        public String size;
        public String color;
        public int unitPrice;
        @Schema(description = "PLACED, PACKED, SHIPPED, OUT_FOR_DELIVERY, DELIVERED or CANCELLED")
        public String status;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Order {
        public String orderId;
        public String customerId;
        public String placedOn;
        @Schema(description = "PLACED, PACKED, SHIPPED, OUT_FOR_DELIVERY, DELIVERED or CANCELLED")
        public String status;
        public String deliveredOn;
        public String expectedDelivery;
        public String carrier;
        public String tracking;
        public String payment;
        public Address address;
        public List<OrderItem> items = new ArrayList<>();
        public int refundTotal;
        public int orderTotal;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Catalogue(List<Product> products, Map<String, ReturnPolicy> returnPolicies, List<Order> orders) {}

    // ---- request / response bodies ----

    public record CancelRequest(
            @Schema(description = "Product ids of the items to cancel. Leave empty to cancel the whole order.")
            List<String> productIds,
            @Schema(description = "Reason given by the customer") String reason) {}

    public record CancelResult(String orderId, String orderStatus, List<String> cancelledItems,
                               int refundAmount, String refundEta) {}

    public record CreateOrderItem(String productId, int qty, String size, String color) {}

    public record CreateOrderRequest(List<CreateOrderItem> items, Address address, String payment) {}

    public record ReturnPolicyView(String category, String productId, String productName, int returnWindowDays,
                                   boolean returnable, boolean exchangeAvailable, String condition,
                                   String refund, String summary) {}

    public record CompareRow(String id, String name, String brand, int price, int mrp, int discountPercent, double rating,
                             int ratingCount, String fit, List<String> sizes, List<String> colors, int stock,
                             int returnWindowDays, boolean exchangeAvailable, List<String> highlights) {}

    public record ItemReturnView(String productId, String name, String itemStatus, int returnWindowDays,
                                 boolean exchangeAvailable, String condition, String returnableUntil,
                                 boolean eligibleNow, String note) {}

    public record SavedAddress(String label, Address address) {}

    public record Facets(java.util.Map<String, Integer> brands, java.util.Map<String, Integer> fits,
                         java.util.Map<String, Integer> colors, int minPrice, int maxPrice) {}

    public record ErrorBody(String error, String message) {}
}
