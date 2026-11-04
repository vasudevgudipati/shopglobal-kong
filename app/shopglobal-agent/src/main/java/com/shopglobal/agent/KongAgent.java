package com.shopglobal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shopglobal.agent.ChatModels.*;
import com.shopglobal.agent.IntentClassifier.Intent;
import com.shopglobal.agent.LlmClient.LlmResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The real shopping assistant: one tool-calling agent.
 * - the model is reached through the Kong AI data plane (OpenAI-compatible /chat/completions)
 * - tools are the marketplace APIs, exposed as MCP tools by Kong
 * - order-changing tools (cancel, change address) are never run on the model's say-so: the customer
 *   must press Confirm in the chat window first, and the stored arguments are what get executed.
 */
@Component
public class KongAgent {

    private static final Logger log = LoggerFactory.getLogger(KongAgent.class);

    private final ObjectMapper mapper;
    private final LlmClient llm;
    private final McpClient mcp;
    private final IntentClassifier classifier;
    private final int maxSteps;
    private final int maxHistory;
    private final List<String> writeTools;

    public KongAgent(ObjectMapper mapper, LlmClient llm, McpClient mcp, IntentClassifier classifier,
                     @Value("${agent.max-steps:6}") int maxSteps,
                     @Value("${agent.max-history-messages:40}") int maxHistory,
                     @Value("${agent.write-tools:cancelOrder,changeDeliveryAddress,placeOrder}") String writeTools) {
        this.mapper = mapper;
        this.llm = llm;
        this.mcp = mcp;
        this.classifier = classifier;
        this.maxSteps = maxSteps;
        this.maxHistory = maxHistory;
        this.writeTools = Arrays.stream(writeTools.split(",")).map(s -> norm(s)).filter(s -> !s.isEmpty()).toList();
    }

    /** Collects what happened during one customer turn, for the UAT debug trace. */
    private static final class Turn {
        final Intent intent;
        final long start = System.currentTimeMillis();
        final List<String> tools = new ArrayList<>();
        final List<String> cache = new ArrayList<>();
        String model = "-";
        int pt, ct;
        Turn(Intent intent) { this.intent = intent; }
        void llm(LlmResult r) { model = r.model(); pt += r.promptTokens(); ct += r.completionTokens(); cache.add(r.cache()); }
        Trace trace() {
            return new Trace(intent.name(), "auto", model, tools, pt, ct, cache, System.currentTimeMillis() - start);
        }
    }

    // ------------------------------------------------------------------ entry points

    public ChatResponse handle(Session s, String userText, String lang) {
        setSystem(s, lang);
        Intent intent = classifier.classify(userText, s.lastIntent);
        s.lastIntent = intent.name();
        Turn turn = new Turn(intent);
        s.messages.add(msg("user", userText));
        int mark = s.messages.size();
        try {
            return run(s, lang, turn).withTrace(turn.trace());
        } catch (KongException e) {
            // roll back this turn so a blocked prompt does not stay in the history
            while (s.messages.size() >= mark) s.messages.remove(s.messages.size() - 1);
            return fromKong(afterTools(e, turn), lang);
        }
    }

    public ChatResponse confirm(Session s, String actionId, boolean approve, String lang) {
        PendingAction p = s.pending;
        if (p == null || !p.id().equals(actionId)) {
            return ChatResponse.ok(lang.equals("hi") ? "पुष्टि करने के लिए कोई लंबित कार्रवाई नहीं है।" : "There is nothing waiting for confirmation.");
        }
        s.pending = null;
        setSystem(s, lang);
        boolean hi = lang.equals("hi");
        // The outcome goes into the history as a tool call + tool result, not as a "user" message: the last message
        // sent to the model is then never a customer-role text, so Kong's semantic cache cannot answer it.
        String callId = "call_" + UUID.randomUUID().toString().replace("-", "");
        String result;
        boolean failed = false;
        if (approve) {
            result = mcp.call(p.tool(), readJson(p.argsJson()), s.customerId);   // runs only now, after the customer pressed Confirm
            failed = result.startsWith("ERROR");
            log.info("Customer confirmed {}; {}", p.tool(), failed ? "it failed" : "it was carried out");
            result = "EXECUTED_AFTER_CUSTOMER_CONFIRMATION. Result: " + limit(result, 4000)
                    + " Tell the customer the outcome briefly (and, if it failed, why).";
        } else {
            log.info("Customer cancelled {}", p.tool());
            result = "NOT_EXECUTED: the customer pressed Cancel. Nothing was changed. Acknowledge briefly and offer other help.";
        }
        int before = s.messages.size();
        s.messages.add(toolCallMsg(callId, p.tool(), p.argsJson()));
        s.messages.add(toolMsg(callId, result));
        Turn turn = new Turn(classifier.of(s.lastIntent == null ? "general" : s.lastIntent));
        try {
            return run(s, lang, turn).withTrace(turn.trace());
        } catch (KongException e) {
            if (approve) {
                // the change already happened: keep it in the history and say so without the model
                while (s.messages.size() > before + 2) s.messages.remove(s.messages.size() - 1);
                String text = failed
                        ? (hi ? "माफ़ कीजिए, यह बदलाव पूरा नहीं हो सका।" : "Sorry, that change could not be completed.")
                        : (hi ? "आपका अनुरोध पूरा हो गया है।" : "Done - your request has been carried out.");
                return ChatResponse.ok(text);
            }
            while (s.messages.size() > before) s.messages.remove(s.messages.size() - 1);
            return fromKong(afterTools(e, turn), lang);
        }
    }

    // ------------------------------------------------------------------ agent loop

    /**
     * A 400 that comes after tools already ran in this turn is not the customer's request being refused (the customer's
     * question was accepted and answered with tool calls): it is the follow-up call that failed, so it is reported as a
     * service problem, not as "I can't help with that".
     */
    private KongException afterTools(KongException e, Turn turn) {
        if (e.status() == 400 && !turn.tools.isEmpty()) {
            log.warn("HTTP 400 on a follow-up model call after tools {} ran - not a refusal of the customer's question", turn.tools);
            return new KongException(502, e.body());
        }
        return e;
    }

    private ChatResponse run(Session s, String lang, Turn turn) {
        trim(s);
        ArrayNode tools = mcp.tools();
        for (int step = 0; step < maxSteps; step++) {
            LlmResult res = llm.chat(s.messages, tools, lang);
            turn.llm(res);
            JsonNode m = res.message();
            ObjectNode copy = assistantCopy(m);
            s.messages.add(copy);
            JsonNode calls = copy.path("tool_calls");
            if (!calls.isArray() || calls.isEmpty()) {
                return ChatResponse.ok(m.path("content").asText("").trim());
            }
            PendingAction pending = null;
            for (JsonNode call : calls) {
                String id = call.path("id").asText(UUID.randomUUID().toString());
                String name = call.path("function").path("name").asText();
                String argsJson = call.path("function").path("arguments").asText("{}");
                if (isWrite(name)) {
                    turn.tools.add(name + " (awaiting Confirm)");
                    if (pending == null) {
                        pending = describe(name, argsJson);
                        s.messages.add(toolMsg(id, "PENDING_CONFIRMATION: the app is now showing the customer a Confirm / Cancel "
                                + "card. Do not call this tool again. Briefly tell the customer what will happen and ask them "
                                + "to press Confirm."));
                    } else {
                        s.messages.add(toolMsg(id, "SKIPPED: only one order change can be confirmed at a time."));
                    }
                } else {
                    turn.tools.add(name);
                    s.messages.add(toolMsg(id, limit(mcp.call(name, readJson(argsJson), s.customerId), 6000)));
                }
            }
            if (pending != null) {
                s.pending = pending;
                // one more model turn, without tools, to word the confirmation request in the customer's language
                LlmResult askRes = llm.chat(s.messages, null, lang);
                turn.llm(askRes);
                JsonNode ask = askRes.message();
                s.messages.add(assistantCopy(ask));
                String text = ask.path("content").asText("").trim();
                if (text.isEmpty()) {
                    text = lang.equals("hi") ? "कृपया नीचे दिए गए कार्ड में पुष्टि करें।" : "Please confirm using the card below.";
                }
                return ChatResponse.ask(text, pending);
            }
        }
        return ChatResponse.ok(lang.equals("hi")
                ? "माफ़ कीजिए, मैं इसे पूरा नहीं कर पाया। कृपया दोबारा कोशिश करें।"
                : "Sorry, I could not complete that. Please try again.");
    }

    // ------------------------------------------------------------------ prompt and messages

    private void setSystem(Session s, String lang) {
        String language = lang.equals("hi") ? "Devanagari typed" : "Roman letters typed";
        String prompt = """
                You are ShopGlobal's shopping assistant on the ShopGlobal marketplace website. Today is %s.
                You help customers find the right product, check order status, understand return policies, change the
                delivery address of an order and cancel an order or single items.

                General rules:
                - LANGUAGE - detect it from the customer's LATEST message and answer in the same language and script:
                    English -> English.
                    Hindi in Devanagari (नमस्ते) -> Hindi in Devanagari.
                    Hinglish, i.e. Hindi written in Roman letters ("aap mujhe jeans suggest kar sakte hai?") -> reply in
                    Hinglish, in Roman letters with easy everyday words, e.g. "Ji haan, mere paas 20 jeans hain. Aap
                    kaunsa fit pasand karenge - Slim ya Straight?". NEVER answer a Hinglish message in English or Devanagari.
                  If the latest message is only an id, a number or a single word, keep the language used earlier in the
                  conversation (English when there is none). Customer's window language hint: %s.
                  Keep product names, brand names and ids (for example ORD-1003, P1005) exactly as they are.
                - This is an ongoing conversation. Use the earlier messages: when the customer says "the second one",
                  "that order", "it" or "the same size", work out what they mean from the conversation so far, and do
                  not ask again for something they already told you.
                - Use the tools for every fact about products, orders, addresses and return policies. Never invent products,
                  prices, order details or policies. If a tool returns nothing, say so.
                - Questions about availability, stock, price, colour or size ("is it available in black?") ALWAYS need a
                  fresh tool call (searchProducts or getProduct) - do not answer them from earlier messages, even when the
                  product was mentioned before. Use product ids from the conversation to call getProduct.
                - LINKS: the chat window turns these into clickable links. Write every product you mention as
                  [Product name](#/product/P1005) and every order as [ORD-1003](#/orders/ORD-1003), using the exact id from a
                  tool result. Never make up an id and never write any other kind of link or web address.
                - Prices are in Indian rupees: write them like Rs 1,499.
                - Be short and friendly. Plain text; use "- " for short lists. Never mention tools, JSON or tool-call ids.

                Finding products (the catalogue has hundreds of products - never dump a list):
                - Call searchProducts with the customer's words as q and with every filter they gave: size (waist or shoe
                  size, e.g. 34), fit, brand, colour, minPrice/maxPrice. Keep limit at 5.
                - The result has `total` (how many match) and `facets` (brands, fits, colours, price range of ALL matches).
                - If total is more than 8 and the customer has not given a brand, fit, colour or budget, do NOT list products.
                  Say how many matched and ask ONE short question that uses the facets, for example which brand or fit
                  (name the 3-4 most common options) or what budget. Then search again with the answer.
                - When you do show products, show at most 3: name, brand, price, fit, rating, available sizes/colours and
                  one short reason it matches. Only recommend products with stock above 0 that have the requested size.
                - If the customer asks to compare, or you are showing 2-3 candidates, call compareProducts with their ids and
                  say in one or two lines which one you would pick and why (price, rating, fit, return window).
                - If nothing matches, say so and suggest the closest alternative by relaxing ONE filter.

                Orders, returns, cancellations and address changes:
                - Only place a new order when the customer clearly asks for it; the app asks them to confirm first.
                - Return policies differ by category. For "can I return..." questions about an order, call
                  getOrderReturnPolicies: an order with four items can have four different policies; answer per item.
                  For a product or category that is not in an order, use getReturnPolicy.
                - "Where is my order" with no order id: call listOrders and summarise, or ask which one if several are open.
                - To cancel or change an address: first read the order (getOrderStatus) and check it is allowed (items not
                  yet shipped). If it is not allowed, explain why and what they can do instead (a return after delivery).
                  If it is allowed, call cancelOrder (with productIds when only some items) or changeDeliveryAddress with the exact order id.
                  The app then shows the customer a Confirm card - do not claim the change is done until a tool result says it was run.
                - "Deliver to address two" / "Address 1": call listSavedAddresses, pick the matching saved address and use it
                  in changeDeliveryAddress; repeat it back (city and pincode) when asking for confirmation.
                """.formatted(java.time.LocalDate.now(), language);
        ObjectNode sys = msg("system", prompt);
        if (!s.messages.isEmpty() && "system".equals(s.messages.get(0).path("role").asText())) {
            s.messages.set(0, sys);
        } else {
            s.messages.add(0, sys);
        }
    }

    /** Keep the history bounded; only cut at a user message so tool-call pairs stay together. */
    private void trim(Session s) {
        while (s.messages.size() > maxHistory) {
            int cut = -1;
            for (int i = 2; i < s.messages.size(); i++) {
                if ("user".equals(s.messages.get(i).path("role").asText())) { cut = i; break; }
            }
            if (cut < 0) return;
            for (int i = 0; i < cut - 1; i++) s.messages.remove(1);
        }
    }

    private ObjectNode msg(String role, String content) {
        ObjectNode n = mapper.createObjectNode();
        n.put("role", role);
        n.put("content", content);
        return n;
    }

    /** An assistant message that contains one tool call (used to record an action the customer confirmed). */
    private ObjectNode toolCallMsg(String callId, String tool, String argsJson) {
        ObjectNode n = msg("assistant", "");
        ObjectNode call = n.putArray("tool_calls").addObject();
        call.put("id", callId);
        call.put("type", "function");
        call.putObject("function").put("name", tool).put("arguments", argsJson == null ? "{}" : argsJson);
        return n;
    }

    /**
     * A tool result is always sent as a JSON OBJECT. Gemini (behind Kong) turns a tool message into a function_response whose
     * "response" must be an object: a bare list (compareProducts returns one) or plain text is rejected with
     * "Proto field is not repeating, cannot start list". An object is accepted by every provider.
     */
    String asJsonObject(String content) {
        String t = content == null ? "" : content.trim();
        try {
            JsonNode n = mapper.readTree(t);
            if (n != null && n.isObject()) return t;
            ObjectNode w = mapper.createObjectNode();
            w.set("result", n == null || n.isMissingNode() ? mapper.getNodeFactory().textNode(t) : n);
            return w.toString();
        } catch (Exception notJson) {
            return mapper.createObjectNode().put("result", t).toString();
        }
    }

    private ObjectNode toolMsg(String callId, String content) {
        ObjectNode n = msg("tool", asJsonObject(content));
        n.put("tool_call_id", callId);
        return n;
    }

    /** Copy only the standard fields, so provider-specific extras do not leak into the next request. */
    private ObjectNode assistantCopy(JsonNode m) {
        ObjectNode n = mapper.createObjectNode();
        n.put("role", "assistant");
        n.put("content", m.path("content").isNull() ? "" : m.path("content").asText(""));
        if (m.path("tool_calls").isArray() && !m.path("tool_calls").isEmpty()) {
            ArrayNode tc = mapper.createArrayNode();
            for (JsonNode c : m.path("tool_calls")) {
                ObjectNode cc = (ObjectNode) c.deepCopy();
                if (!cc.hasNonNull("id")) cc.put("id", "call_" + UUID.randomUUID().toString().replace("-", ""));
                if (!cc.hasNonNull("type")) cc.put("type", "function");
                tc.add(cc);
            }
            n.set("tool_calls", tc);
        }
        return n;
    }

    // ------------------------------------------------------------------ write-tool handling

    private boolean isWrite(String toolName) {
        String n = norm(toolName);
        for (String w : writeTools) if (n.contains(w)) return true;
        return false;
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /** Builds the text the customer sees on the Confirm card from the model's arguments. */
    private PendingAction describe(String tool, String argsJson) {
        JsonNode a = readJson(argsJson);
        List<String> details = new ArrayList<>();
        String order = firstText(a, "orderId", "order_id", "id");
        String title;
        if (norm(tool).contains("cancel")) {
            title = "Cancel " + (order.isEmpty() ? "order" : "order " + order);
            JsonNode ids = firstNode(a, "productIds", "product_ids", "items");
            if (ids != null && ids.isArray() && !ids.isEmpty()) {
                List<String> l = new ArrayList<>();
                ids.forEach(x -> l.add(x.asText()));
                details.add("Items: " + String.join(", ", l));
            } else {
                details.add("Whole order");
            }
            String reason = firstText(a, "reason");
            if (!reason.isEmpty()) details.add("Reason: " + reason);
        } else if (norm(tool).contains("placeorder")) {
            title = "Place a new order";
            JsonNode items = firstNode(a, "items", "lines", "products");
            if (items != null && items.isArray()) {
                items.forEach(x -> details.add(x.path("productId").asText(x.path("id").asText(x.isValueNode() ? x.asText() : "item"))
                        + (x.has("quantity") ? " x " + x.path("quantity").asText() : "")));
            }
        } else {
            title = "Change delivery address" + (order.isEmpty() ? "" : " of order " + order);
            JsonNode addr = a.has("address") ? a.get("address") : a;
            for (String k : new String[]{"name", "line1", "line2", "city", "state", "pincode", "phone"}) {
                String v = addr.path(k).asText("");
                if (!v.isEmpty()) details.add(v);
            }
        }
        return new PendingAction(UUID.randomUUID().toString(), tool, argsJson, title, details);
    }

    private String firstText(JsonNode n, String... keys) {
        for (String k : keys) if (n.hasNonNull(k) && n.get(k).isValueNode()) return n.get(k).asText();
        return "";
    }

    private JsonNode firstNode(JsonNode n, String... keys) {
        for (String k : keys) if (n.hasNonNull(k)) return n.get(k);
        return null;
    }

    private JsonNode readJson(String json) {
        try {
            JsonNode n = mapper.readTree(json == null || json.isBlank() ? "{}" : json);
            return n == null ? mapper.createObjectNode() : n;
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    // ------------------------------------------------------------------ Kong refusals

    /**
     * What the customer sees when the gateway refuses or fails. Gateway text is never shown (the prompt guard's reply
     * is terse and not meant for customers); only the app's own messages are. Only the status code is logged.
     */
    private ChatResponse fromKong(KongException e, String lang) {
        boolean hi = lang.equals("hi");
        int st = e.status();
        log.warn("Request to the gateway ended with HTTP {}", st);
        if (st == 400) {
            return new ChatResponse(hi ? "माफ़ कीजिए, मैं इस अनुरोध में मदद नहीं कर सकता।"
                    : "Sorry, I can't help with that request.", null, true, "blocked");
        }
        if (st == 429) {
            return new ChatResponse(hi ? "अभी बहुत अनुरोध आ रहे हैं। कृपया थोड़ी देर में दोबारा कोशिश करें।"
                    : "I'm getting a lot of requests right now. Please try again in a moment.", null, true, "quota");
        }
        return new ChatResponse(hi ? "सहायक अभी उपलब्ध नहीं है। कृपया थोड़ी देर बाद कोशिश करें।"
                : "The assistant is not available right now. Please try again in a moment.", null, false, "unavailable");
    }

    private static String limit(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "...(truncated)" : s;
    }
}
