package com.shopglobal.mockapi;

import com.shopglobal.mockapi.Models.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * REST controllers. operationId values become the MCP tool names when Kong converts the published API to
 * MCP tools, so they are short, verb-first and unambiguous. No identity yet: the customer comes from the
 * optional X-Customer-Id header and defaults to the demo customer.
 */
public class ApiControllers {

    static final String DEFAULT_CUSTOMER = "CUST-1001";

    @RestController
    @RequestMapping("/api")
    @Tag(name = "Products")
    public static class ProductController {
        private final ShopService shop;
        public ProductController(ShopService shop) { this.shop = shop; }

        public record SearchResponse(int total, int returned, String hint, Facets facets, List<Product> products) {}

        @GetMapping("/products")
        @Operation(operationId = "searchProducts", summary = "Search the product catalogue (returns the best few, not everything)",
                description = "Natural keywords (for example 'slim chinos') plus optional filters. Returns at most `limit` products "
                        + "(default 5) ranked by relevance, plus the total match count and facets (brands, fits, colours, price range) "
                        + "so the caller can narrow down instead of listing hundreds of products. Use size for waist or shoe size, e.g. 34.")
        public SearchResponse search(
                @Parameter(description = "Free-text search, e.g. 'slim fit trousers'") @RequestParam(required = false) String q,
                @Parameter(description = "Category or sub-category, e.g. Trousers, Footwear, Electronics") @RequestParam(required = false) String category,
                @RequestParam(required = false) String brand,
                @Parameter(description = "Exact size, e.g. 34, M, 9") @RequestParam(required = false) String size,
                @Parameter(description = "Fit, e.g. Slim, Regular, Relaxed, Tapered, Straight") @RequestParam(required = false) String fit,
                @Parameter(description = "Colour, e.g. Navy") @RequestParam(required = false) String color,
                @RequestParam(required = false) Integer minPrice,
                @RequestParam(required = false) Integer maxPrice,
                @Parameter(description = "relevance, price_asc, price_desc, rating or discount") @RequestParam(required = false) String sort,
                @Parameter(description = "How many products to return (default 5, max 200)") @RequestParam(required = false) Integer limit) {
            List<Product> all = shop.search(q, category, brand, size, fit, color, minPrice, maxPrice, sort);
            int lim = limit == null ? 5 : Math.max(1, Math.min(limit, 200));
            List<Product> top = all.size() > lim ? all.subList(0, lim) : all;
            Map<String, Integer> brands = new TreeMap<>(), fits = new TreeMap<>(), colors = new TreeMap<>();
            int lo = Integer.MAX_VALUE, hi = 0;
            for (Product p : all) {
                brands.merge(p.brand(), 1, Integer::sum);
                if (p.fit() != null) fits.merge(p.fit(), 1, Integer::sum);
                p.colors().forEach(c -> colors.merge(c, 1, Integer::sum));
                lo = Math.min(lo, p.price());
                hi = Math.max(hi, p.price());
            }
            String hint = all.size() > lim
                    ? all.size() + " products match; showing the top " + lim + ". Narrow down with brand, fit, colour, price or size, or compare shortlisted ones."
                    : null;
            return new SearchResponse(all.size(), top.size(), hint,
                    new Facets(brands, fits, colors, all.isEmpty() ? 0 : lo, hi), top);
        }

        @GetMapping("/products/compare")
        @Operation(operationId = "compareProducts", summary = "Compare 2 to 4 products side by side",
                description = "Price, discount, rating, fit, sizes, colours, stock and return window for each product id.")
        public List<CompareRow> compare(@Parameter(description = "Comma separated product ids, e.g. P1001,P1002") @RequestParam String ids) {
            return shop.compare(java.util.Arrays.asList(ids.split(",")));
        }

        @GetMapping("/products/{productId}")
        @Operation(operationId = "getProduct", summary = "Get one product by id")
        public Product get(@PathVariable String productId) { return shop.product(productId); }

        @GetMapping("/categories")
        @Operation(operationId = "listCategories", summary = "List product categories")
        public List<String> categories() { return shop.categories(); }
    }

    @RestController
    @RequestMapping("/api")
    @Tag(name = "Returns")
    public static class ReturnController {
        private final ShopService shop;
        public ReturnController(ShopService shop) { this.shop = shop; }

        @GetMapping("/return-policy")
        @Operation(operationId = "getReturnPolicy", summary = "Get the return policy for a product or category",
                description = "Policy depends on the product category, so an order with four items can have four different policies. "
                        + "Pass productId for a specific item, or category.")
        public ReturnPolicyView policy(@RequestParam(required = false) String productId,
                                       @RequestParam(required = false) String category) {
            return shop.returnPolicy(productId, category);
        }
    }

    @RestController
    @RequestMapping("/api/orders")
    @Tag(name = "Orders")
    public static class OrderController {
        private final ShopService shop;
        public OrderController(ShopService shop) { this.shop = shop; }

        @GetMapping
        @Operation(operationId = "listOrders", summary = "List the customer's orders, newest first")
        public List<Order> list(@RequestHeader(value = "X-Customer-Id", required = false) String c) {
            return shop.orders(c == null ? DEFAULT_CUSTOMER : c);
        }

        @GetMapping("/{orderId}")
        @Operation(operationId = "getOrderStatus", summary = "Get an order with item-level status and delivery details",
                description = "Item status is one of PLACED, PACKED, SHIPPED, OUT_FOR_DELIVERY, DELIVERED, CANCELLED. Orders with items not yet shipped can still be cancelled.")
        public Order get(@PathVariable String orderId, @RequestHeader(value = "X-Customer-Id", required = false) String c) {
            return shop.order(orderId, c == null ? DEFAULT_CUSTOMER : c);
        }

        @GetMapping("/{orderId}/return-policies")
        @Operation(operationId = "getOrderReturnPolicies", summary = "Return policy of every item in an order",
                description = "One row per item: return window, exchange, condition, last return date and whether it can be returned today. "
                        + "An order with four items can have four different policies.")
        public List<ItemReturnView> returns(@PathVariable String orderId, @RequestHeader(value = "X-Customer-Id", required = false) String c) {
            return shop.orderReturns(orderId, c == null ? DEFAULT_CUSTOMER : c);
        }

        @PostMapping
        @Operation(operationId = "placeOrder", summary = "Place a new order (used by the storefront checkout)")
        public Order create(@RequestBody CreateOrderRequest r, @RequestHeader(value = "X-Customer-Id", required = false) String c) {
            return shop.create(c == null ? DEFAULT_CUSTOMER : c, r);
        }

        @PostMapping("/{orderId}/cancel")
        @Operation(operationId = "cancelOrder", summary = "Cancel a whole order or selected items (only if not yet shipped)",
                description = "Changes the order and triggers a refund. Allowed only for items in PLACED or PACKED state. "
                        + "Leave productIds empty to cancel the whole order. The customer must confirm before this is called.")
        public CancelResult cancel(@PathVariable String orderId, @RequestBody(required = false) CancelRequest r,
                                   @RequestHeader(value = "X-Customer-Id", required = false) String c) {
            return shop.cancel(orderId, c == null ? DEFAULT_CUSTOMER : c, r);
        }

        @PutMapping("/{orderId}/address")
        @Operation(operationId = "changeDeliveryAddress", summary = "Change the delivery address of an order (only before it ships)",
                description = "Needs name, line1, city, state, phone and a 6-digit pincode. The customer must confirm before this is called.")
        public Order address(@PathVariable String orderId, @RequestBody Address a,
                             @RequestHeader(value = "X-Customer-Id", required = false) String c) {
            return shop.changeAddress(orderId, c == null ? DEFAULT_CUSTOMER : c, a);
        }
    }

    @RestController
    @RequestMapping("/api")
    @Tag(name = "Addresses")
    public static class AddressController {
        private final ShopService shop;
        public AddressController(ShopService shop) { this.shop = shop; }

        @GetMapping("/addresses")
        @Operation(operationId = "listSavedAddresses", summary = "The customer's saved addresses (Address 1 - Home, Address 2 - Office)",
                description = "Use this to resolve requests like 'deliver to address two instead'.")
        public List<SavedAddress> addresses(@RequestHeader(value = "X-Customer-Id", required = false) String c) {
            return shop.addresses(c == null ? DEFAULT_CUSTOMER : c);
        }
    }
}
