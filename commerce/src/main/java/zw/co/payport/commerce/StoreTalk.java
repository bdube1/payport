package zw.co.payport.commerce;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The store website's side of chat with the seller, reports of a store, and saving a product (the heart). */
@Controller
public class StoreTalk {

    private final Shop shop;
    private final Chats chats;
    private final Shoppers shoppers;
    private final Storefront storefront;

    public StoreTalk(Shop shop, Chats chats, Shoppers shoppers, Storefront storefront) {
        this.shop = shop;
        this.chats = chats;
        this.shoppers = shoppers;
        this.storefront = storefront;
    }

    private Chats.Who who(String me, String token) {
        Optional<Shoppers.Shopper> s = shoppers.current(me);
        return s.map(x -> new Chats.Who(x.id(), null, null, x.name(), "WEB")).orElseGet(() -> new Chats.Who(null, token, null, null, "WEB"));
    }

    private static String chatToken(String token, HttpServletResponse res) {
        if (token != null && token.matches("[A-Za-z0-9_-]{20,80}")) return token;
        String t = Shop.token() + Shop.token();
        Cookie c = new Cookie(Chats.COOKIE, t);
        c.setHttpOnly(true);
        c.setSecure(true);
        c.setPath("/");
        c.setMaxAge(60 * 60 * 24 * 90);
        c.setAttribute("SameSite", "Lax");
        res.addCookie(c);
        return t;
    }

    @GetMapping("/s/{slug}/chat")
    public String chat(@PathVariable String slug, @RequestParam(name = "p", required = false) Long productId,
                       @CookieValue(name = Shoppers.COOKIE, required = false) String me, @CookieValue(name = Chats.COOKIE, required = false) String token,
                       Model model, HttpServletResponse res) {
        return page(slug, productId, me, token, null, model, res);
    }

    String page(String slug, Long productId, String me, String token, String problem, Model model, HttpServletResponse res) {
        Optional<Map<String, Object>> s = shop.publicStore(slug);
        if (s.isEmpty()) return storefront.missing(model, res, "This store does not exist");
        Map<String, Object> st = s.get();
        Chats.Who w = who(me, token);
        Optional<Map<String, Object>> c = chats.find(((Number) st.get("id")).longValue(), w);
        List<Map<String, Object>> messages = c.map(x -> chats.messages(((Number) x.get("id")).longValue(), 0)).orElse(List.of());
        c.ifPresent(x -> chats.customerRead(((Number) x.get("id")).longValue()));
        String about = null;
        if (productId != null) about = shop.jdbc().queryForList("SELECT name FROM products WHERE id = ? AND store_id = ? AND status = 'ACTIVE'", String.class, productId, st.get("id"))
                .stream().findFirst().orElse(null);
        Map<String, Object> sm = storefront.storeModel(st);
        model.addAttribute("store", sm);
        model.addAttribute("me", storefront.meModel());
        model.addAttribute("messages", messages);
        model.addAttribute("lastId", messages.isEmpty() ? 0 : messages.get(messages.size() - 1).get("id"));
        model.addAttribute("started", c.isPresent());
        model.addAttribute("productId", about == null ? null : productId);
        model.addAttribute("about", about);
        model.addAttribute("problem", problem);
        model.addAttribute("title", "Chat with " + st.get("name"));
        model.addAttribute("share", storefront.share("/s/" + st.get("slug"), st.get("name") + " on Payport"));
        res.setHeader("Cache-Control", "no-store");
        return "chat";
    }

    @PostMapping("/s/{slug}/chat")
    public String send(@PathVariable String slug, @RequestParam(name = "text", required = false) String text, @RequestParam(name = "name", required = false) String name,
                       @RequestParam(name = "p", required = false) Long productId,
                       @CookieValue(name = Shoppers.COOKIE, required = false) String me, @CookieValue(name = Chats.COOKIE, required = false) String token,
                       Model model, HttpServletResponse res) {
        Optional<Map<String, Object>> s = shop.publicStore(slug);
        if (s.isEmpty()) return storefront.missing(model, res, "This store does not exist");
        String t = chatToken(token, res);
        Chats.Who w = who(me, t);
        if (w.shopperId() == null) w = new Chats.Who(null, t, null, name, "WEB");
        try {
            Long pid = productId == null ? null : shop.jdbc().queryForList("SELECT id FROM products WHERE id = ? AND store_id = ?", Long.class, productId, s.get().get("id"))
                    .stream().findFirst().orElse(null);
            chats.customerSays(s.get(), w, text, pid);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return page(slug, productId, me, t, e.getMessage(), model, res);
        }
        return "redirect:/s/" + s.get().get("slug") + "/chat#end";
    }

    /** New messages for the open chat page (it asks every few seconds). */
    @GetMapping("/s/{slug}/chat/messages")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> poll(@PathVariable String slug, @RequestParam(name = "after", defaultValue = "0") long after,
                                                    @CookieValue(name = Shoppers.COOKIE, required = false) String me, @CookieValue(name = Chats.COOKIE, required = false) String token) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("messages", List.of());
        shop.publicStore(slug).flatMap(st -> chats.find(((Number) st.get("id")).longValue(), who(me, token))).ifPresent(c -> {
            long id = ((Number) c.get("id")).longValue();
            out.put("messages", chats.messages(id, after));
            chats.customerRead(id);
        });
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }

    // ─── reports ─────────────────────────────────────────────────────────────

    @GetMapping("/s/{slug}/report")
    public String reportForm(@PathVariable String slug, Model model, HttpServletResponse res) {
        return reportPage(slug, null, null, model, res);
    }

    String reportPage(String slug, String problem, String ref, Model model, HttpServletResponse res) {
        Optional<Map<String, Object>> s = shop.publicStore(slug);
        if (s.isEmpty()) return storefront.missing(model, res, "This store does not exist");
        model.addAttribute("store", storefront.storeModel(s.get()));
        model.addAttribute("me", storefront.meModel());
        model.addAttribute("reasons", Chats.REASON_NAMES);
        model.addAttribute("problem", problem);
        model.addAttribute("ref", ref);
        model.addAttribute("done", ref != null || (problem == null && model.containsAttribute("sent")));
        model.addAttribute("title", "Report " + s.get().get("name"));
        res.setHeader("Cache-Control", "no-store");
        return "report";
    }

    @PostMapping("/s/{slug}/report")
    public String report(@PathVariable String slug, @RequestParam(name = "reason", required = false) String reason, @RequestParam(name = "details", required = false) String details,
                         @RequestParam(name = "phone", required = false) String phone, @CookieValue(name = Shoppers.COOKIE, required = false) String me,
                         @CookieValue(name = Chats.COOKIE, required = false) String token, Model model, HttpServletResponse res) {
        Optional<Map<String, Object>> s = shop.publicStore(slug);
        if (s.isEmpty()) return storefront.missing(model, res, "This store does not exist");
        Optional<Shoppers.Shopper> sh = shoppers.current(me);
        String p = phone == null || phone.isBlank() ? sh.map(Shoppers.Shopper::phone).orElse(null) : phone.trim();
        if (p != null && !p.replaceAll("[\\s()+-]", "").matches("[0-9]{9,15}")) return reportPage(slug, "Enter a valid phone number, or leave it empty.", null, model, res);
        Long chatId = chats.find(((Number) s.get().get("id")).longValue(), who(me, token)).map(c -> ((Number) c.get("id")).longValue()).orElse(null);
        String ref;
        try {
            ref = chats.report(s.get(), "CUSTOMER", chatId, reason, details, p);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return reportPage(slug, e.getMessage(), null, model, res);
        }
        model.addAttribute("sent", true);
        return reportPage(slug, null, ref == null ? "" : ref, model, res);
    }

    // ─── saving a product (the heart) ────────────────────────────────────────

    @PostMapping("/s/{slug}/{product}/save")
    public String save(@PathVariable String slug, @PathVariable String product, @CookieValue(name = Shoppers.COOKIE, required = false) String me) {
        Optional<Shoppers.Shopper> s = shoppers.current(me);
        String back = "/s/" + slug + "/" + product;
        if (s.isEmpty()) return "redirect:/signin?next=" + URLEncoder.encode(back, StandardCharsets.UTF_8);
        shop.publicStore(slug).flatMap(st -> shop.publicProduct(((Number) st.get("id")).longValue(), product))
                .ifPresent(p -> shoppers.toggleSaved(s.get(), ((Number) p.get("id")).longValue()));
        return "redirect:" + back;
    }
}
