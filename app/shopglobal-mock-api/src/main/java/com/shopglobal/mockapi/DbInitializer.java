package com.shopglobal.mockapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopglobal.mockapi.Models.Catalogue;
import com.shopglobal.mockapi.Models.Order;
import com.shopglobal.mockapi.Models.Product;
import com.shopglobal.mockapi.Models.ReturnPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.Map;

/**
 * Creates the tables and sequence if they are missing and loads the seed data (catalogue.json) when the
 * tables are empty. Oracle 11g XE.
 */
@Component
public class DbInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DbInitializer.class);

    private static final String[] DDL = {
            "CREATE TABLE PRODUCTS (ID VARCHAR2(10) PRIMARY KEY, NAME VARCHAR2(200) NOT NULL, BRAND VARCHAR2(100), "
                    + "CATEGORY VARCHAR2(60), SUB_CATEGORY VARCHAR2(60), PRICE NUMBER(10) NOT NULL, MRP NUMBER(10) NOT NULL, "
                    + "RATING NUMBER(3,1), RATING_COUNT NUMBER(10), COLORS VARCHAR2(500), SIZES VARCHAR2(200), "
                    + "SIZE_TYPE VARCHAR2(50), FIT VARCHAR2(50), DESCRIPTION VARCHAR2(1000), HIGHLIGHTS VARCHAR2(1000), "
                    + "STOCK NUMBER(10), TAG VARCHAR2(30))",
            "CREATE TABLE RETURN_POLICIES (CATEGORY VARCHAR2(60) PRIMARY KEY, RETURN_DAYS NUMBER(3) NOT NULL, "
                    + "COND_TEXT VARCHAR2(500), REFUND VARCHAR2(300), EXCHANGE_OK NUMBER(1) NOT NULL)",
            "CREATE TABLE ORDERS (ORDER_ID VARCHAR2(20) PRIMARY KEY, CUSTOMER_ID VARCHAR2(30) NOT NULL, PLACED_ON VARCHAR2(10), "
                    + "STATUS VARCHAR2(20) NOT NULL, DELIVERED_ON VARCHAR2(10), EXPECTED_DELIVERY VARCHAR2(10), "
                    + "CARRIER VARCHAR2(50), TRACKING VARCHAR2(50), PAYMENT VARCHAR2(20), ORDER_TOTAL NUMBER(12), "
                    + "REFUND_TOTAL NUMBER(12) DEFAULT 0, ADDR_NAME VARCHAR2(100), ADDR_LINE1 VARCHAR2(200), "
                    + "ADDR_LINE2 VARCHAR2(200), ADDR_CITY VARCHAR2(100), ADDR_STATE VARCHAR2(100), "
                    + "ADDR_PINCODE VARCHAR2(6), ADDR_PHONE VARCHAR2(15))",
            "CREATE TABLE ORDER_ITEMS (ORDER_ID VARCHAR2(20) NOT NULL, LINE_NO NUMBER(3) NOT NULL, PRODUCT_ID VARCHAR2(10) NOT NULL, "
                    + "NAME VARCHAR2(200), BRAND VARCHAR2(100), QTY NUMBER(5) NOT NULL, ITEM_SIZE VARCHAR2(20), "
                    + "COLOR VARCHAR2(100), UNIT_PRICE NUMBER(10) NOT NULL, STATUS VARCHAR2(20) NOT NULL, "
                    + "PRIMARY KEY (ORDER_ID, LINE_NO))",
            "CREATE SEQUENCE ORDER_SEQ START WITH 1101 INCREMENT BY 1"
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ShopService shop;

    public DbInitializer(JdbcTemplate jdbc, ObjectMapper mapper, ShopService shop) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.shop = shop;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        for (String ddl : DDL) createIfMissing(ddl);
        Catalogue c = readCatalogue();
        if (count("PRODUCTS") == 0) {
            c.products().forEach(this::insertProduct);
            c.returnPolicies().forEach(this::insertPolicy);
            log.info("Loaded {} products and {} return policies", c.products().size(), c.returnPolicies().size());
        }
        if (count("ORDERS") == 0) {
            seedOrders(c);
        }
    }

    /** Deletes all orders (including ones created through checkout) and loads the seed orders again. */
    public void resetDemoOrders() throws Exception {
        jdbc.update("DELETE FROM ORDER_ITEMS");
        jdbc.update("DELETE FROM ORDERS");
        seedOrders(readCatalogue());
    }

    private void seedOrders(Catalogue c) {
        // seed dates are written for ANCHOR; shift them so "today" is always the demo day
        long shift = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(ANCHOR), java.time.LocalDate.now());
        for (Order o : c.orders()) {
            o.placedOn = shiftDate(o.placedOn, shift);
            o.deliveredOn = shiftDate(o.deliveredOn, shift);
            o.expectedDelivery = shiftDate(o.expectedDelivery, shift);
            shop.insertOrder(o);
        }
        log.info("Loaded {} seed orders", c.orders().size());
    }

    private static final String ANCHOR = "2026-11-04";

    private static String shiftDate(String d, long days) {
        return d == null ? null : java.time.LocalDate.parse(d).plusDays(days).toString();
    }

    private Catalogue readCatalogue() throws Exception {
        try (InputStream in = new ClassPathResource("catalogue.json").getInputStream()) {
            return mapper.readValue(in, Catalogue.class);
        }
    }

    private int count(String table) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return n == null ? 0 : n;
    }

    private void createIfMissing(String ddl) {
        try {
            jdbc.execute(ddl);
        } catch (DataAccessException e) {
            if (alreadyExists(e)) return;   // the table/sequence is already there (every restart after the first)
            throw e;
        }
    }

    /** ORA-00955 = name already used. Spring 6 no longer puts the cause text into getMessage(), so the whole cause chain is checked. */
    private static boolean alreadyExists(Throwable t) {
        for (int i = 0; t != null && i < 10; i++, t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && (m.contains("ORA-00955") || m.toLowerCase().contains("already exists"))) return true;
        }
        return false;
    }

    private void insertProduct(Product p) {
        jdbc.update("INSERT INTO PRODUCTS (ID, NAME, BRAND, CATEGORY, SUB_CATEGORY, PRICE, MRP, RATING, RATING_COUNT, COLORS, SIZES, "
                        + "SIZE_TYPE, FIT, DESCRIPTION, HIGHLIGHTS, STOCK, TAG) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                p.id(), p.name(), p.brand(), p.category(), p.subCategory(), p.price(), p.mrp(), p.rating(), p.ratingCount(),
                String.join("|", p.colors()), String.join("|", p.sizes()), p.sizeType(), p.fit(), p.description(),
                String.join("|", p.highlights()), p.stock(), p.tag());
    }

    private void insertPolicy(String category, ReturnPolicy r) {
        jdbc.update("INSERT INTO RETURN_POLICIES (CATEGORY, RETURN_DAYS, COND_TEXT, REFUND, EXCHANGE_OK) VALUES (?,?,?,?,?)",
                category, r.days(), r.condition(), r.refund(), r.exchange() ? 1 : 0);
    }
}
