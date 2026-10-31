package zw.co.payport.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Payport AI in the store (owner, 31 Oct 2026): the shopping assistant behind the search box (plain words such as
 * "school shoes for my son delivered" find the right stores and items, with a short answer), and the writer of product
 * descriptions for merchants.
 *
 * With COMMERCE_AI_API_KEY set, a language model writes the answer and the descriptions; it only ever sees the
 * customer's question and public catalogue facts (names, prices, store names), never a name, phone number or order.
 * Without a key (or if the model does not answer in time) the built-in assistant answers, so the store never waits on it.
 */
@Service
public class Ai {

    private static final Logger log = LoggerFactory.getLogger(Ai.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Plain words people use, mapped to the words merchants use in their catalogues. */
    static final Map<String, List<String>> WORDS = new LinkedHashMap<>();

    static {
        WORDS.put("kids", List.of("kid", "child", "children", "baby", "boys", "girls", "school", "toddler"));
        WORDS.put("son", List.of("boys", "kid", "child", "school"));
        WORDS.put("daughter", List.of("girls", "kid", "child", "school"));
        WORDS.put("clothes", List.of("clothing", "shirt", "t-shirt", "dress", "jeans", "jacket", "uniform", "wear"));
        WORDS.put("shoes", List.of("shoe", "sneakers", "boots", "sandals", "takkies"));
        WORDS.put("food", List.of("meal", "lunch", "dinner", "breakfast", "sadza", "rice", "chicken", "beef", "kitchen"));
        WORDS.put("hungry", List.of("meal", "lunch", "dinner", "food", "takeaway"));
        WORDS.put("drink", List.of("drinks", "juice", "soda", "coffee", "tea", "water", "beverage", "brew"));
        WORDS.put("coffee", List.of("brew", "espresso", "latte", "cafe"));
        WORDS.put("bread", List.of("bakery", "loaf", "buns", "cake", "pastry"));
        WORDS.put("cake", List.of("bakery", "birthday", "pastry"));
        WORDS.put("hair", List.of("salon", "braids", "haircut", "cut", "wig", "barber"));
        WORDS.put("beauty", List.of("makeup", "skin", "nails", "salon", "lotion", "perfume"));
        WORDS.put("phone", List.of("smartphone", "mobile", "charger", "airtime", "electronics"));
        WORDS.put("laptop", List.of("computer", "electronics", "notebook"));
        WORDS.put("gift", List.of("present", "hamper", "flowers", "crafts"));
    }

    static final Set<String> CHEAP = Set.of("cheap", "cheapest", "affordable", "budget", "low");
    static final Set<String> STOP = Set.of("i", "a", "an", "the", "for", "my", "me", "to", "of", "and", "in", "on", "with", "want", "need", "looking",
            "some", "any", "please", "find", "buy", "get", "where", "can", "is", "are", "there", "near", "today", "now", "that", "who", "sells", "sell");

    private final String key, model, url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    public Ai(@Value("${commerce.ai.api-key:}") String key, @Value("${commerce.ai.model:claude-sonnet-5-5}") String model,
              @Value("${commerce.ai.url:https://api.anthropic.com/v1/messages}") String url) {
        this.key = key == null ? "" : key.trim();
        this.model = model;
        this.url = url;
    }

    public boolean modelOn() {
        return !key.isEmpty();
    }

    /** What the customer asked, read as a search: the words to look for, and what they care about. */
    public record Query(String text, List<String> terms, boolean cheap, boolean delivery, boolean cashOnDelivery, String type) {}

    public Query read(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.length() > 200) text = text.substring(0, 200);
        String low = text.toLowerCase(Locale.ROOT);
        boolean cod = low.contains("pay on delivery") || low.contains("cash on delivery") || low.contains("pay cash");
        boolean delivery = cod || low.contains("deliver");
        Set<String> terms = new LinkedHashSet<>();
        boolean cheap = false;
        String type = null;
        for (String w : low.split("[^a-z0-9'-]+")) {
            if (w.isBlank() || STOP.contains(w)) continue;
            if (CHEAP.contains(w)) { cheap = true; continue; }
            if (w.startsWith("deliver") || w.equals("cash")) continue;
            terms.add(w);
            if (w.endsWith("s") && w.length() > 3) terms.add(w.substring(0, w.length() - 1));
            List<String> more = WORDS.get(w);
            if (more != null) terms.addAll(more);
            if (type == null) type = switch (w) {
                case "food", "hungry", "meal", "lunch", "dinner", "breakfast", "bakery", "bread", "cake" -> "FOOD";
                case "drink", "drinks", "juice", "coffee", "tea" -> "DRINKS";
                case "clothes", "clothing", "shoes", "dress", "shirt" -> "CLOTHING";
                case "hair", "salon", "beauty", "makeup", "nails" -> "BEAUTY";
                case "phone", "laptop", "electronics", "charger" -> "ELECTRONICS";
                default -> null;
            };
        }
        return new Query(text, new ArrayList<>(terms), cheap, delivery, cod, type);
    }

    /**
     * A short answer for the customer about what was found. The model writes it when switched on; otherwise (or if it
     * does not answer within a few seconds) it is written here from the results themselves.
     */
    public String answer(Query q, List<Map<String, Object>> items, int storeCount) {
        String local = localAnswer(q, items, storeCount);
        if (!modelOn() || items.isEmpty()) return local;
        StringBuilder facts = new StringBuilder();
        int n = 0;
        for (Map<String, Object> it : items) {
            if (n++ >= 12) break;
            facts.append("- ").append(it.get("name")).append(", ").append(it.get("price")).append(", at ").append(it.get("store"))
                    .append(Boolean.TRUE.equals(it.get("cod")) ? " (pay on delivery available)" : "").append('\n');
        }
        String prompt = "A shopper on Payport Store (Zimbabwe) asked: \"" + q.text().replace("\"", "'") + "\".\nMatching items:\n" + facts
                + "\nIn at most two short sentences, tell the shopper which items fit best and where. Use only the items listed; "
                + "do not invent products, prices or stores. Plain text, no lists, no greetings.";
        String out = complete(prompt, 160);
        return out == null || out.isBlank() ? local : out.trim();
    }

    static String localAnswer(Query q, List<Map<String, Object>> items, int storeCount) {
        if (q.text().isBlank()) return null;
        if (items.isEmpty() && storeCount == 0) return "Nothing matches \"" + q.text() + "\" yet. Try fewer words, or browse the categories below.";
        if (items.isEmpty()) return storeCount + (storeCount == 1 ? " store matches" : " stores match") + " \"" + q.text() + "\".";
        Map<String, Object> best = items.get(0);
        long stores = items.stream().map(i -> i.get("store")).distinct().count();
        String lead = items.size() + (items.size() == 1 ? " item" : " items") + " in " + stores + (stores == 1 ? " store" : " stores");
        String pick = (q.cheap() ? "The lowest price is " : "Top match: ") + best.get("name") + " at " + best.get("store") + " for " + best.get("price");
        String extra = q.cashOnDelivery() ? " All of these let you pay on delivery." : q.delivery() ? " Only stores that deliver are shown." : "";
        return lead + ". " + pick + "." + extra;
    }

    /** A product description for a merchant to edit: from the model when switched on, otherwise a clear starting text. */
    public String describe(String name, String category, String kind, String notes, String storeName) {
        String n = Shop.text(name, 120, true, "product name");
        String c = Shop.text(category, 40, false, "category");
        String no = Shop.text(notes, 400, false, "notes");
        boolean service = "SERVICE".equals(kind);
        if (modelOn()) {
            String prompt = "Write a product description for a small business's online store in Zimbabwe.\nStore: " + (storeName == null ? "" : storeName)
                    + "\n" + (service ? "Service" : "Product") + ": " + n + (c == null ? "" : "\nCategory: " + c) + (no == null ? "" : "\nMerchant's notes: " + no)
                    + "\n\nTwo or three short sentences, warm and plain, that say what it is and why a customer would want it. Use only the facts given; "
                    + "no prices, no made-up claims, no emojis, no headings.";
            String out = complete(prompt, 220);
            if (out != null && !out.isBlank()) return Shop.clip(out.trim(), 2000);
        }
        StringBuilder b = new StringBuilder();
        b.append(n).append(service ? " — booked online and paid securely with Payport." : " — order online and pay securely with Payport.");
        if (no != null) b.append(' ').append(Character.toUpperCase(no.charAt(0))).append(no.substring(1)).append(no.endsWith(".") ? "" : ".");
        if (c != null) b.append(" Part of our ").append(c.toLowerCase(Locale.ROOT)).append(" range.");
        return b.toString();
    }

    // ─── chat: what customers ask, and messages that need a look ────────────

    /** Topics customers ask sellers about, with the words that point to each (checked in this order). */
    static final Map<String, List<String>> TOPICS = new LinkedHashMap<>();

    static {
        TOPICS.put("RETURNS", List.of("refund", "return", "exchange", "swap", "broken", "faulty", "damaged", "wrong item"));
        TOPICS.put("ORDER", List.of("my order", "track", "status", "when will", "not received", "still waiting", "order number", "co-"));
        TOPICS.put("DELIVERY", List.of("deliver", "delivery", "transport", "courier", "shipping", "send it", "bring it", "collect", "pickup", "pick up"));
        TOPICS.put("PAYMENT", List.of("pay", "ecocash", "onemoney", "omari", "innbucks", "card", "wallet", "cash", "installment", "lay-by", "layby", "deposit"));
        TOPICS.put("PRICE", List.of("price", "how much", "cost", "discount", "cheaper", "best price", "negotiable", "last price", "promo", "deal"));
        TOPICS.put("AVAILABILITY", List.of("available", "in stock", "stock", "do you have", "still have", "any left", "sold out", "when back"));
        TOPICS.put("DETAILS", List.of("size", "colour", "color", "material", "original", "genuine", "warranty", "specs", "fit", "weight", "made of", "model"));
        TOPICS.put("LOCATION", List.of("where", "location", "address", "shop is", "directions", "open", "hours", "closing", "weekend", "sunday"));
    }

    public static final Map<String, String> TOPIC_NAMES = Map.of("RETURNS", "Returns and refunds", "ORDER", "An order", "DELIVERY", "Delivery and pickup",
            "PAYMENT", "Paying", "PRICE", "Price", "AVAILABILITY", "In stock", "DETAILS", "Product details", "LOCATION", "Location and hours", "OTHER", "Other");

    /** What a customer's message is about (for the merchant's "what customers ask" and Payport's own view). */
    public String topic(String text) {
        String low = " " + (text == null ? "" : text.toLowerCase(Locale.ROOT)) + " ";
        for (Map.Entry<String, List<String>> e : TOPICS.entrySet())
            for (String w : e.getValue()) if (low.contains(w)) return e.getKey();
        return "OTHER";
    }

    static final List<String> OFF_PLATFORM = List.of("pay me directly", "send money to", "send the money to", "pay outside", "pay to my number",
            "pay on my number", "my ecocash number", "my bank account", "account number", "western union", "mukuru", "pay before i deliver to");
    static final List<String> ABUSE = List.of("idiot", "stupid", "fool", "useless", "i will kill", "kill you", "scammer", "thief", "bitch", "fuck", "shit", "pussy");

    /** A message to look at: asking to be paid outside Payport (where customers lose their protection), or abuse. */
    public String flag(String text) {
        String low = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String w : OFF_PLATFORM) if (low.contains(w)) return "OFF_PLATFORM";
        for (String w : ABUSE) if (low.matches("(?s).*\\b" + java.util.regex.Pattern.quote(w) + "\\b.*")) return "ABUSE";
        return null;
    }

    /** One call to the language model; null when it is off, slow or refuses. */
    String complete(String prompt, int maxTokens) {
        try {
            Map<String, Object> body = Map.of("model", model, "max_tokens", maxTokens,
                    "messages", List.of(Map.of("role", "user", "content", prompt)));
            HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(6))
                    .header("content-type", "application/json").header("x-api-key", key).header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build();
            HttpResponse<String> res = http.send(r, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                log.info("[COMMERCE] AI answered {}", res.statusCode());
                return null;
            }
            JsonNode n = JSON.readTree(res.body());
            StringBuilder out = new StringBuilder();
            for (JsonNode c : n.path("content")) if ("text".equals(c.path("type").asText())) out.append(c.path("text").asText());
            return out.toString();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.info("[COMMERCE] AI not reached: {}", e.getMessage());
            return null;
        }
    }
}
