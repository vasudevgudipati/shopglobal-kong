package com.shopglobal.agent;

import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides what the customer wants BEFORE the model is called, with simple rules (no model call, no cost).
 * The result is used only for the UAT debug trace and for follow-up questions. It is NOT sent to Kong and does not
 * choose a model: the agent always asks for "shopglobal-auto" and Kong routes by meaning.
 */
@Component
public class IntentClassifier {

    private static final Pattern CANCEL = Pattern.compile("cancel|रद्द|निरस्त|radd karo|raddh");
    private static final Pattern ADDRESS = Pattern.compile("address|deliver (it |this |the order )?to|पता|पते");
    private static final Pattern RETURN = Pattern.compile("return|refund|exchange|replace|policy|रिटर्न|वापस|रिफंड|पॉलिसी|wapas|vapas|refund");
    private static final Pattern ORDER = Pattern.compile("order|track|where is|status|delivery|delivered|ord-\\d+|ऑर्डर|ट्रैक|कहाँ|कहां|स्थिति|kahan|kab tak|kahaan");
    private static final Pattern PRODUCT = Pattern.compile(
            "find|show|search|compare|buy|looking|recommend|suggest|which|best|under|below|budget|size|fit|brand|colou?r|trouser|pant|chino|jean|shirt|shoe|sneaker|"
                    + "dress|kurta|phone|headphone|earbud|watch|jacket|खोज|दिखा|चाहिए|तुलना|खरीद|ट्राउज़र|जींस|शर्ट|जूते|dikhao|chahiye|available|mein|kharid|sasta");

    public record Intent(String name, String tier) {}

    public Intent classify(String text, String last) {
        String t = text.toLowerCase(Locale.ROOT);
        if (CANCEL.matcher(t).find()) return of("cancel_order");
        if (ADDRESS.matcher(t).find() && (t.contains("change") || t.contains("instead") || t.contains("update") || t.contains("deliver")
                || t.contains("बदल") || t.matches(".*address\\s*(1|2|one|two|\\d).*") || t.contains("दो"))) return of("change_address");
        if (RETURN.matcher(t).find()) return of("return_policy");
        if (ORDER.matcher(t).find()) return of("order_status");
        if (PRODUCT.matcher(t).find()) return of("product_search");
        // short follow-ups ("yes", "the second one", "navy please") stay on the previous topic
        if (last != null && !last.equals("general") && t.length() <= 60) return of(last);
        return of("general");
    }

    public Intent of(String name) {
        return new Intent(name, switch (name) {
            case "product_search", "change_address", "cancel_order" -> "frontier";
            case "order_status", "general" -> "small";
            case "return_policy" -> "cache";
            default -> "small";
        });
    }
}
