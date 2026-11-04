package com.shopglobal.mockapi;

import com.shopglobal.mockapi.Models.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Business rules over the database (Oracle 11g XE).
 * Plain JDBC with simple SQL that works on both: VARCHAR2 / NUMBER columns, ROWNUM-free, no identity columns.
 */
@Service
public class ShopService {

    private static final Set<String> STOP = Set.of("a", "an", "the", "of", "in", "for", "me", "my", "find", "show",
            "i", "want", "need", "looking", "pair", "inch", "inches", "with", "and", "to", "please", "some", "any");
    private static final Map<String, String> SYNONYMS = Map.of("pants", "trouser", "pant", "trouser",
            "tshirt", "t-shirt", "sneaker", "sneaker", "trainers", "sneaker", "cellphone", "phone");
    private static final Set<String> CANCELLABLE = Set.of("PLACED", "PACKED");

    static final String ORDER_COLS = "ORDER_ID, CUSTOMER_ID, PLACED_ON, STATUS, DELIVERED_ON, EXPECTED_DELIVERY, CARRIER, TRACKING, "
            + "PAYMENT, ORDER_TOTAL, REFUND_TOTAL, ADDR_NAME, ADDR_LINE1, ADDR_LINE2, ADDR_CITY, ADDR_STATE, ADDR_PINCODE, ADDR_PHONE";

    private final JdbcTemplate jdbc;

    public ShopService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ row mappers

    private static List<String> split(String s) {
        return s == null || s.isBlank() ? List.of() : Arrays.asList(s.split("\\|"));
    }

    private static final RowMapper<Product> PRODUCT = (rs, i) -> new Product(
            rs.getString("ID"), rs.getString("NAME"), rs.getString("BRAND"), rs.getString("CATEGORY"), rs.getString("SUB_CATEGORY"),
            rs.getInt("PRICE"), rs.getInt("MRP"), rs.getDouble("RATING"), rs.getInt("RATING_COUNT"),
            split(rs.getString("COLORS")), split(rs.getString("SIZES")), rs.getString("SIZE_TYPE"), rs.getString("FIT"),
            rs.getString("DESCRIPTION"), split(rs.getString("HIGHLIGHTS")), rs.getInt("STOCK"), rs.getString("TAG"));

    private static final RowMapper<Order> ORDER = (rs, i) -> {
        Order o = new Order();
        o.orderId = rs.getString("ORDER_ID");
        o.customerId = rs.getString("CUSTOMER_ID");
        o.placedOn = rs.getString("PLACED_ON");
        o.status = rs.getString("STATUS");
        o.deliveredOn = rs.getString("DELIVERED_ON");
        o.expectedDelivery = rs.getString("EXPECTED_DELIVERY");
        o.carrier = rs.getString("CARRIER");
        o.tracking = rs.getString("TRACKING");
        o.payment = rs.getString("PAYMENT");
        o.orderTotal = rs.getInt("ORDER_TOTAL");
        o.refundTotal = rs.getInt("REFUND_TOTAL");
        Address a = new Address();
        a.name = rs.getString("ADDR_NAME");
        a.line1 = rs.getString("ADDR_LINE1");
        a.line2 = rs.getString("ADDR_LINE2");
        a.city = rs.getString("ADDR_CITY");
        a.state = rs.getString("ADDR_STATE");
        a.pincode = rs.getString("ADDR_PINCODE");
        a.phone = rs.getString("ADDR_PHONE");
        o.address = a;
        return o;
    };

    private static final RowMapper<OrderItem> ITEM = (rs, i) -> {
        OrderItem it = new OrderItem();
        it.lineNo = rs.getInt("LINE_NO");
        it.productId = rs.getString("PRODUCT_ID");
        it.name = rs.getString("NAME");
        it.brand = rs.getString("BRAND");
        it.qty = rs.getInt("QTY");
        it.size = rs.getString("ITEM_SIZE");
        it.color = rs.getString("COLOR");
        it.unitPrice = rs.getInt("UNIT_PRICE");
        it.status = rs.getString("STATUS");
        return it;
    };

    // ------------------------------------------------------------------ products

    private List<Product> products() {
        return jdbc.query("SELECT * FROM PRODUCTS ORDER BY ID", PRODUCT);
    }

    public List<String> categories() {
        return products().stream().map(Product::category).distinct().sorted().collect(Collectors.toList());
    }

    public Product product(String id) {
        List<Product> l = jdbc.query("SELECT * FROM PRODUCTS WHERE UPPER(ID) = UPPER(?)", PRODUCT, id);
        if (l.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "No product with id " + id);
        return l.get(0);
    }

    /** Hits (all of them, sorted) – the controller applies the limit and builds facets from the full list. */
    public List<Product> search(String q, String category, String brand, String size, String fit, String color,
                                Integer minPrice, Integer maxPrice, String sort) {
        List<String> tokens = tokens(q);
        List<Product> base = products().stream()
                .filter(p -> blank(category) || p.category().equalsIgnoreCase(category) || p.subCategory().equalsIgnoreCase(category))
                .filter(p -> blank(brand) || p.brand().equalsIgnoreCase(brand))
                .filter(p -> blank(size) || p.sizes().contains(size))
                .filter(p -> blank(fit) || (p.fit() != null && p.fit().equalsIgnoreCase(fit)))
                .filter(p -> blank(color) || p.colors().stream().anyMatch(c -> c.toLowerCase(Locale.ROOT).contains(color.toLowerCase(Locale.ROOT))))
                .filter(p -> minPrice == null || p.price() >= minPrice)
                .filter(p -> maxPrice == null || p.price() <= maxPrice)
                .collect(Collectors.toList());

        Map<String, Integer> score = new HashMap<>();
        List<Product> hits = new ArrayList<>();
        if (tokens.isEmpty()) {
            hits.addAll(base);
        } else {
            for (Product p : base) {
                int s = score(p, tokens);
                if (s == tokens.size()) { score.put(p.id(), s); hits.add(p); }
            }
            if (hits.isEmpty()) {                      // relax: any token
                for (Product p : base) {
                    int s = score(p, tokens);
                    if (s > 0) { score.put(p.id(), s); hits.add(p); }
                }
            }
        }
        Comparator<Product> byRelevance = Comparator
                .comparing((Product p) -> -score.getOrDefault(p.id(), 0))
                .thenComparing(p -> -p.rating());
        Comparator<Product> cmp = switch (sort == null ? "" : sort) {
            case "price_asc" -> Comparator.comparingInt(Product::price);
            case "price_desc" -> Comparator.comparingInt(Product::price).reversed();
            case "rating" -> Comparator.comparingDouble(Product::rating).reversed();
            case "discount" -> Comparator.comparingDouble((Product p) -> 1.0 - (double) p.price() / p.mrp()).reversed();
            default -> byRelevance;
        };
        hits.sort(cmp);
        return hits;
    }

    private List<String> tokens(String q) {
        if (blank(q)) return List.of();
        List<String> out = new ArrayList<>();
        for (String t : q.toLowerCase(Locale.ROOT).split("[^a-z0-9\\-]+")) {
            if (t.isBlank() || STOP.contains(t)) continue;
            t = SYNONYMS.getOrDefault(t, t);
            if (t.length() > 3 && t.endsWith("s")) t = t.substring(0, t.length() - 1);
            out.add(t);
        }
        return out;
    }

    private int score(Product p, List<String> tokens) {
        String hay = (p.name() + " " + p.brand() + " " + p.category() + " " + p.subCategory() + " " + p.fit() + " "
                + p.description() + " " + String.join(" ", p.colors()) + " " + String.join(" ", p.highlights()))
                .toLowerCase(Locale.ROOT);
        int s = 0;
        for (String t : tokens) {
            boolean numeric = t.chars().allMatch(Character::isDigit);
            if (numeric ? p.sizes().contains(t) : hay.contains(t)) s++;
        }
        return s;
    }

    // ------------------------------------------------------------------ compare / per-order returns / addresses

    public List<CompareRow> compare(List<String> ids) {
        if (ids == null || ids.size() < 2 || ids.size() > 4) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "BAD_IDS", "Give 2 to 4 product ids");
        }
        List<CompareRow> rows = new ArrayList<>();
        for (String id : ids) {
            Product p = product(id.trim());
            ReturnPolicyView rp = returnPolicy(p.id(), null);
            int off = (int) Math.round(100.0 * (p.mrp() - p.price()) / p.mrp());
            rows.add(new CompareRow(p.id(), p.name(), p.brand(), p.price(), p.mrp(), off, p.rating(), p.ratingCount(), p.fit(),
                    p.sizes(), p.colors(), p.stock(), rp.returnWindowDays(), rp.exchangeAvailable(), p.highlights()));
        }
        return rows;
    }

    public List<ItemReturnView> orderReturns(String orderId, String customerId) {
        Order o = order(orderId, customerId);
        LocalDate today = LocalDate.now();
        List<ItemReturnView> out = new ArrayList<>();
        for (OrderItem it : o.items) {
            ReturnPolicyView rp = returnPolicy(it.productId, null);
            String until = null;
            boolean eligible = false;
            String note;
            if ("CANCELLED".equals(it.status)) {
                note = "Item was cancelled";
            } else if (rp.returnWindowDays() == 0) {
                note = rp.condition();
            } else if (!"DELIVERED".equals(it.status) || blank(o.deliveredOn)) {
                note = "Not delivered yet. Once delivered it can be returned within " + rp.returnWindowDays() + " days";
            } else {
                LocalDate u = LocalDate.parse(o.deliveredOn).plusDays(rp.returnWindowDays());
                until = u.toString();
                eligible = !today.isAfter(u);
                note = eligible ? "Eligible for return until " + until : "Return window closed on " + until;
            }
            out.add(new ItemReturnView(it.productId, it.name, it.status, rp.returnWindowDays(), rp.exchangeAvailable(),
                    rp.condition(), until, eligible, note));
        }
        return out;
    }

    /** Saved addresses of the demo customers. An unknown customer has none. */
    public List<SavedAddress> addresses(String customerId) {
        List<SavedAddress> l = SAVED.get(customerId == null ? "" : customerId);
        return l == null ? List.of() : l;
    }

    private static Address addr(String name, String line1, String line2, String city, String state, String pincode, String phone) {
        Address a = new Address();
        a.name = name; a.line1 = line1; a.line2 = line2; a.city = city; a.state = state; a.pincode = pincode; a.phone = phone;
        return a;
    }

    private static final Map<String, List<SavedAddress>> SAVED = Map.of(
            "CUST-1001", List.of(
                    new SavedAddress("Address 1 - Home", addr("Vasudev Gudipati", "Flat 402, Lakeview Residency", "Madhapur", "Hyderabad", "Telangana", "500081", "9876543210")),
                    new SavedAddress("Address 2 - Office", addr("Vasudev Gudipati", "7th Floor, Prestige Tech Park", "Outer Ring Road, Marathahalli", "Bengaluru", "Karnataka", "560103", "9876543210"))),
            "CUST-1002", List.of(
                    new SavedAddress("Address 1 - Home", addr("Tataji", "B-12, Sea Breeze Apartments", "Andheri West", "Mumbai", "Maharashtra", "400053", "9876500002")),
                    new SavedAddress("Address 2 - Office", addr("Tataji", "4th Floor, Rajiv Gandhi Infotech Park", "Hinjewadi Phase 1", "Pune", "Maharashtra", "411057", "9876500002"))),
            "CUST-1003", List.of(
                    new SavedAddress("Address 1 - Home", addr("Shubham", "C-45, Green Park Extension", "Green Park", "New Delhi", "Delhi", "110016", "9876500003")),
                    new SavedAddress("Address 2 - Office", addr("Shubham", "Tower B, DLF Cyber City", "Phase 2", "Gurugram", "Haryana", "122002", "9876500003"))),
            "CUST-1004", List.of(
                    new SavedAddress("Address 1 - Home", addr("Kvamshi", "No. 21, Lake View Road", "Velachery", "Chennai", "Tamil Nadu", "600042", "9876500004")),
                    new SavedAddress("Address 2 - Office", addr("Kvamshi", "5th Floor, Embassy Tech Village", "Outer Ring Road, Bellandur", "Bengaluru", "Karnataka", "560103", "9876500004"))));

    // ------------------------------------------------------------------ return policy

    public ReturnPolicyView returnPolicy(String productId, String category) {
        Product p = null;
        if (!blank(productId)) {
            p = product(productId);
            category = p.category();
        }
        if (blank(category)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER", "Provide productId or category");
        }
        final String cat = category;
        List<ReturnPolicy> l = jdbc.query(
                "SELECT RETURN_DAYS, COND_TEXT, REFUND, EXCHANGE_OK FROM RETURN_POLICIES WHERE UPPER(CATEGORY) = UPPER(?)",
                (rs, i) -> new ReturnPolicy(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getInt(4) == 1), cat);
        if (l.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "No policy for category " + cat);
        ReturnPolicy rp = l.get(0);
        boolean returnable = rp.days() > 0;
        String summary = returnable
                ? "You can return this item within " + rp.days() + " days of delivery. Condition: " + rp.condition()
                  + ". " + rp.refund() + ". " + (rp.exchange() ? "Exchange is available." : "Exchange is not available.")
                : rp.condition() + ".";
        return new ReturnPolicyView(cat, p == null ? null : p.id(), p == null ? null : p.name(), rp.days(), returnable,
                rp.exchange(), rp.condition(), rp.refund(), summary);
    }

    // ------------------------------------------------------------------ orders

    public List<Order> orders(String customerId) {
        List<Order> l = jdbc.query("SELECT " + ORDER_COLS + " FROM ORDERS WHERE CUSTOMER_ID = ? ORDER BY PLACED_ON DESC, ORDER_ID DESC",
                ORDER, customerId);
        l.forEach(this::fillItems);
        return l;
    }

    public Order order(String orderId, String customerId) {
        List<Order> l = jdbc.query("SELECT " + ORDER_COLS + " FROM ORDERS WHERE UPPER(ORDER_ID) = UPPER(?) AND CUSTOMER_ID = ?",
                ORDER, orderId, customerId);
        if (l.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "No order " + orderId + " for this customer");
        }
        Order o = l.get(0);
        fillItems(o);
        return o;
    }

    private void fillItems(Order o) {
        o.items = jdbc.query("SELECT LINE_NO, PRODUCT_ID, NAME, BRAND, QTY, ITEM_SIZE, COLOR, UNIT_PRICE, STATUS "
                + "FROM ORDER_ITEMS WHERE ORDER_ID = ? ORDER BY LINE_NO", ITEM, o.orderId);
    }

    /** Row lock so two requests cannot change the same order at once. Throws 404 when the order is not the customer's. */
    private void lock(String orderId, String customerId) {
        List<String> l = jdbc.query("SELECT ORDER_ID FROM ORDERS WHERE UPPER(ORDER_ID) = UPPER(?) AND CUSTOMER_ID = ? FOR UPDATE",
                (rs, i) -> rs.getString(1), orderId, customerId);
        if (l.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", "No order " + orderId + " for this customer");
        }
    }

    @Transactional
    public CancelResult cancel(String orderId, String customerId, CancelRequest req) {
        lock(orderId, customerId);
        Order o = order(orderId, customerId);
        List<String> ids = req == null || req.productIds() == null ? List.of() : req.productIds();
        List<OrderItem> targets = new ArrayList<>();
        if (ids.isEmpty()) {
            targets.addAll(o.items.stream().filter(i -> !"CANCELLED".equals(i.status)).toList());
        } else {
            for (String id : ids) {
                OrderItem it = o.items.stream()
                        .filter(i -> i.productId.equalsIgnoreCase(id) && !targets.contains(i)).findFirst()
                        .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ITEM_NOT_FOUND", "Order " + o.orderId + " has no item " + id));
                targets.add(it);
            }
        }
        if (targets.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "NOTHING_TO_CANCEL", "Order " + o.orderId + " has no active items");
        }
        for (OrderItem it : targets) {
            if (!CANCELLABLE.contains(it.status)) {
                throw new ApiException(HttpStatus.CONFLICT, "NOT_CANCELLABLE",
                        it.name + " is already " + it.status + " and can no longer be cancelled. Once delivered, use the return policy instead.");
            }
        }
        int refund = 0;
        List<String> names = new ArrayList<>();
        for (OrderItem it : targets) {
            jdbc.update("UPDATE ORDER_ITEMS SET STATUS = 'CANCELLED' WHERE ORDER_ID = ? AND LINE_NO = ?", o.orderId, it.lineNo);
            it.status = "CANCELLED";
            refund += it.unitPrice * it.qty;
            names.add(it.name);
        }
        o.refundTotal += refund;
        if (o.items.stream().allMatch(i -> "CANCELLED".equals(i.status))) o.status = "CANCELLED";
        jdbc.update("UPDATE ORDERS SET REFUND_TOTAL = ?, STATUS = ? WHERE ORDER_ID = ?", o.refundTotal, o.status, o.orderId);
        return new CancelResult(o.orderId, o.status, names, refund, "5-7 business days to the original payment method");
    }

    @Transactional
    public Order changeAddress(String orderId, String customerId, Address a) {
        lock(orderId, customerId);
        Order o = order(orderId, customerId);
        if (!CANCELLABLE.contains(o.status)) {
            throw new ApiException(HttpStatus.CONFLICT, "ADDRESS_LOCKED",
                    "Order " + o.orderId + " is " + o.status + "; the delivery address can only be changed before it ships.");
        }
        validate(a);
        jdbc.update("UPDATE ORDERS SET ADDR_NAME = ?, ADDR_LINE1 = ?, ADDR_LINE2 = ?, ADDR_CITY = ?, ADDR_STATE = ?, "
                        + "ADDR_PINCODE = ?, ADDR_PHONE = ? WHERE ORDER_ID = ?",
                a.name, a.line1, blankToNull(a.line2), a.city, a.state, a.pincode, a.phone, o.orderId);
        return order(o.orderId, customerId);
    }

    @Transactional
    public Order create(String customerId, CreateOrderRequest r) {
        if (r == null || r.items() == null || r.items().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_ORDER", "Order has no items");
        }
        validate(r.address());
        Long seq = jdbc.queryForObject("SELECT ORDER_SEQ.NEXTVAL FROM DUAL", Long.class);
        Order o = new Order();
        o.orderId = "ORD-" + seq;
        o.customerId = customerId;
        o.placedOn = LocalDate.now().toString();
        o.expectedDelivery = LocalDate.now().plusDays(5).toString();
        o.status = "PLACED";
        o.payment = blank(r.payment()) ? "COD" : r.payment();
        o.address = r.address();
        int line = 1;
        for (CreateOrderItem ci : r.items()) {
            Product p = product(ci.productId());
            OrderItem it = new OrderItem();
            it.lineNo = line++;
            it.productId = p.id(); it.name = p.name(); it.brand = p.brand();
            it.qty = Math.max(1, ci.qty()); it.size = ci.size(); it.color = ci.color();
            it.unitPrice = p.price(); it.status = "PLACED";
            o.items.add(it);
        }
        o.orderTotal = o.items.stream().mapToInt(i -> i.unitPrice * i.qty).sum();
        insertOrder(o);
        return o;
    }

    /** Inserts an order with its items. Used by checkout and by the seed / reset code. */
    public void insertOrder(Order o) {
        int total = o.items.stream().mapToInt(i -> i.unitPrice * i.qty).sum();
        Address a = o.address;
        jdbc.update("INSERT INTO ORDERS (" + ORDER_COLS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                o.orderId, o.customerId, o.placedOn, o.status, o.deliveredOn, o.expectedDelivery, o.carrier, o.tracking,
                o.payment, total, o.refundTotal, a.name, a.line1, blankToNull(a.line2), a.city, a.state, a.pincode, a.phone);
        int line = 1;
        for (OrderItem it : o.items) {
            jdbc.update("INSERT INTO ORDER_ITEMS (ORDER_ID, LINE_NO, PRODUCT_ID, NAME, BRAND, QTY, ITEM_SIZE, COLOR, UNIT_PRICE, STATUS) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                    o.orderId, line++, it.productId, it.name, it.brand, it.qty, it.size, it.color, it.unitPrice, it.status);
        }
    }

    private void validate(Address a) {
        if (a == null || blank(a.name) || blank(a.line1) || blank(a.city) || blank(a.state) || blank(a.phone)
                || a.pincode == null || !a.pincode.matches("\\d{6}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_ADDRESS",
                    "Address needs name, line1, city, state, phone and a 6-digit pincode");
        }
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String blankToNull(String s) { return blank(s) ? null : s; }
}
