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
    @org.springframework.beans.factory.annotation.Autowired Growth growth;
    @org.springframework.beans.factory.annotation.Autowired ChatFiles files;

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
        // Contact seller (owner, 1 Nov 2026): conversations belong to the customer's account, kept on their Messages page.
        Optional<Shoppers.Shopper> who = shoppers.current(me);
        if (who.isEmpty()) return "redirect:/signin?next=" + URLEncoder.encode("/s/" + slug + "/chat", StandardCharsets.UTF_8);
        Optional<Map<String, Object>> st = shop.publicStore(slug);
        if (st.isEmpty()) return storefront.missing(model, res, "This store does not exist");
        Optional<Map<String, Object>> c = chats.find(((Number) st.get().get("id")).longValue(), who(me, token));
        if (c.isPresent()) return "redirect:/messages?c=" + c.get().get("id");
        return inbox(who.get(), null, st.get(), productId, null, model, res);
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
        if (shoppers.current(me).isEmpty()) return "redirect:/signin?next=" + URLEncoder.encode("/s/" + slug + "/chat", StandardCharsets.UTF_8);
        String t = token;
        Chats.Who w = who(me, t);
        try {
            Long pid = productId == null ? null : shop.jdbc().queryForList("SELECT id FROM products WHERE id = ? AND store_id = ?", Long.class, productId, s.get().get("id"))
                    .stream().findFirst().orElse(null);
            chats.customerSays(s.get(), w, text, pid);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return inbox(shoppers.current(me).get(), null, s.get(), productId, e.getMessage(), model, res);
        }
        return chats.find(((Number) s.get().get("id")).longValue(), w).map(c -> "redirect:/messages?c=" + c.get("id"))
                .orElse("redirect:/s/" + s.get().get("slug") + "/chat");
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

    // ─── the customer's messages (every conversation, for the record) ────────

    @GetMapping("/messages")
    public String messages(@RequestParam(name = "c", required = false) Long chatId, @CookieValue(name = Shoppers.COOKIE, required = false) String me,
                           Model model, HttpServletResponse res) {
        Optional<Shoppers.Shopper> s = shoppers.current(me);
        if (s.isEmpty()) return "redirect:/signin?next=/messages";
        return inbox(s.get(), chatId, null, null, null, model, res);
    }

    /** The WhatsApp-style page: conversations on the left, the open one on the right (or a new one with a store). */
    String inbox(Shoppers.Shopper who, Long chatId, Map<String, Object> newStore, Long productId, String problem, Model model, HttpServletResponse res) {
        List<Map<String, Object>> list = chats.forShopper(who.id());
        Map<String, Object> open = null;
        if (chatId != null) {
            Optional<Map<String, Object>> c = chats.ofShopper(chatId, who.id());
            if (c.isPresent()) {
                long id = ((Number) c.get().get("id")).longValue();
                chats.customerRead(id);
                open = list.stream().filter(x -> ((Number) x.get("id")).longValue() == id).findFirst().map(LinkedHashMap::new).orElse(null);
                if (open != null) {
                    List<Map<String, Object>> msgs = chats.messages(id, 0);
                    open.put("messages", msgs);
                    open.put("lastId", msgs.isEmpty() ? 0 : msgs.get(msgs.size() - 1).get("id"));
                    open.put("unread", 0);
                    for (Map<String, Object> x : list) if (((Number) x.get("id")).longValue() == id) x.put("unread", 0);
                }
            }
        }
        if (open == null && newStore != null) {
            open = new LinkedHashMap<>();
            open.put("id", 0);
            open.put("store", newStore.get("name"));
            open.put("slug", newStore.get("slug"));
            open.put("avatar", files.avatar("MERCHANT", ((Number) newStore.get("merchant_id")).longValue()));
            open.put("messages", List.of());
            open.put("lastId", 0);
            if (productId != null) open.put("about", shop.jdbc().queryForList("SELECT name FROM products WHERE id = ? AND store_id = ? AND status = 'ACTIVE'",
                    String.class, productId, newStore.get("id")).stream().findFirst().orElse(null));
            open.put("productId", productId);
        }
        model.addAttribute("me", storefront.meModel());
        model.addAttribute("myAvatar", files.avatar("SHOPPER", who.id()));
        model.addAttribute("myInitial", who.name() == null || who.name().isBlank() ? "Me" : who.name().trim().substring(0, 1).toUpperCase(java.util.Locale.ROOT));
        model.addAttribute("chats", list);
        model.addAttribute("open", open);
        model.addAttribute("problem", problem);
        model.addAttribute("emojis", EMOJIS);
        model.addAttribute("title", "Messages | Payport Store");
        res.setHeader("Cache-Control", "no-store");
        return "messages";
    }

    static final List<String> EMOJIS = List.of("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "😉", "😍", "🥰", "😘", "😋", "😛", "😜",
            "🤗", "🤔", "🤨", "😐", "😏", "😒", "🙄", "😬", "😌", "😔", "😴", "😷", "🤒", "😎", "🤓", "😕", "😟", "😮", "😲", "😳", "🥺", "😢", "😭",
            "😱", "😤", "😡", "👍", "👎", "👌", "✌️", "🤞", "👏", "🙌", "🙏", "💪", "👋", "🤝", "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "💯", "✅",
            "❌", "⭐", "🔥", "🎉", "🎁", "📦", "🚚", "🛒", "💰", "💵", "💳", "📱", "📍", "⏰", "📅", "☀️", "🌧️", "🍔", "🍕", "🍗", "🥤", "☕",
            "👗", "👟", "👜", "💄", "🏠", "🚗", "⚽", "🎵");

    private Optional<Long> myChat(String me, long chatId) {
        return shoppers.current(me).flatMap(s -> chats.ofShopper(chatId, s.id())).map(c -> ((Number) c.get("id")).longValue());
    }

    private static ResponseEntity<Map<String, Object>> problem(int status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("message", message));
    }

    /** The open conversation's new messages, and the list (unread counts), every few seconds. */
    @GetMapping("/messages/{id}/poll")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> inboxPoll(@PathVariable long id, @RequestParam(name = "after", defaultValue = "0") long after,
                                                         @CookieValue(name = Shoppers.COOKIE, required = false) String me) {
        Optional<Shoppers.Shopper> s = shoppers.current(me);
        if (s.isEmpty()) return problem(401, "Sign in again");
        Map<String, Object> out = new LinkedHashMap<>();
        if (id > 0 && chats.ofShopper(id, s.get().id()).isPresent()) {
            out.put("messages", chats.messages(id, after));
            chats.customerRead(id);
        } else out.put("messages", List.of());
        out.put("chats", chats.forShopper(s.get().id()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
    }

    @PostMapping("/messages/{id}/text")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> inboxText(@PathVariable long id, @RequestParam(name = "text", required = false) String text,
                                                         @CookieValue(name = Shoppers.COOKIE, required = false) String me) {
        Optional<Long> c = myChat(me, id);
        if (c.isEmpty()) return problem(404, "No such conversation");
        try {
            chats.customerSaysIn(c.get(), text);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return problem(400, e.getMessage());
        }
    }

    @PostMapping("/messages/{id}/files")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> inboxFile(@PathVariable long id, @RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                                         @RequestParam(name = "caption", required = false) String caption,
                                                         @RequestParam(name = "voice", required = false) String voice,
                                                         @CookieValue(name = Shoppers.COOKIE, required = false) String me) throws java.io.IOException {
        Optional<Long> c = myChat(me, id);
        if (c.isEmpty()) return problem(404, "No such conversation");
        try {
            ChatFiles.Stored f = files.accept(c.get(), file.getBytes(), file.getOriginalFilename(), "1".equals(voice), 0);
            String text = caption == null || caption.isBlank() ? f.name() : Chats.body(caption);
            chats.customerSendsExtra(c.get(), f.kind(), f.id(), text);
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return problem(400, e.getMessage());
        }
    }

    @PostMapping("/messages/{id}/contact")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> inboxContact(@PathVariable long id, @RequestParam(name = "name", required = false) String name,
                                                            @RequestParam(name = "phone", required = false) String phone,
                                                            @CookieValue(name = Shoppers.COOKIE, required = false) String me) {
        Optional<Long> c = myChat(me, id);
        if (c.isEmpty()) return problem(404, "No such conversation");
        try {
            chats.customerSendsExtra(c.get(), "CONTACT", null, contactText(name, phone));
            return ResponseEntity.ok(Map.of("ok", true));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return problem(400, e.getMessage());
        }
    }

    /** A shared contact: a name and a phone number. */
    static String contactText(String name, String phone) {
        String n = name == null ? "" : name.replaceAll("\\p{Cntrl}", " ").trim();
        String p = phone == null ? "" : phone.replaceAll("[^0-9+ ]", "").trim();
        if (n.isEmpty() || n.length() > 60) throw new IllegalArgumentException("Enter the contact's name");
        if (p.replaceAll("\\D", "").length() < 7 || p.length() > 20) throw new IllegalArgumentException("Enter the contact's phone number");
        return n + "\n" + p;
    }

    @PostMapping("/me/avatar")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> avatar(@RequestParam("file") org.springframework.web.multipart.MultipartFile file,
                                                      @CookieValue(name = Shoppers.COOKIE, required = false) String me) throws java.io.IOException {
        Optional<Shoppers.Shopper> s = shoppers.current(me);
        if (s.isEmpty()) return problem(401, "Sign in again");
        try {
            files.setAvatar("SHOPPER", s.get().id(), files.acceptAvatar(file.getBytes()));
            return ResponseEntity.ok(Map.of("avatar", files.avatar("SHOPPER", s.get().id())));
        } catch (IllegalArgumentException e) {
            return problem(400, e.getMessage());
        }
    }

    /** A chat file or profile picture, through its signed link (valid for an hour). */
    @GetMapping("/chat-files/{id}")
    public ResponseEntity<org.springframework.core.io.Resource> chatFile(@PathVariable long id, @RequestParam(name = "e", defaultValue = "0") long e,
                                                                         @RequestParam(name = "s", required = false) String sig) {
        if (!files.linkValid(id, e, sig)) return ResponseEntity.status(403).build();
        Optional<ChatFiles.File> f = files.read(id);
        if (f.isEmpty()) return ResponseEntity.notFound().build();
        boolean inline = !"DOCUMENT".equals(f.get().kind());
        String name = f.get().name() == null ? "file" : f.get().name();
        return ResponseEntity.ok()
                .contentType(org.springframework.http.MediaType.parseMediaType(f.get().contentType()))
                .header("Content-Disposition", org.springframework.http.ContentDisposition.builder(inline ? "inline" : "attachment")
                        .filename(name, StandardCharsets.UTF_8).build().toString())
                .header("Content-Security-Policy", "default-src 'none'; img-src 'self'; media-src 'self'; sandbox")
                .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePrivate())
                .body(new org.springframework.core.io.ByteArrayResource(f.get().bytes()));
    }

    // ─── rating a store, reviewing an item ───────────────────────────────────

    @PostMapping("/s/{slug}/rate")
    public String rate(@PathVariable String slug, @RequestParam(name = "stars", required = false) String stars,
                       @CookieValue(name = Shoppers.COOKIE, required = false) String me) {
        Optional<Shoppers.Shopper> s = shoppers.current(me);
        if (s.isEmpty()) return "redirect:/signin?next=" + URLEncoder.encode("/s/" + slug, StandardCharsets.UTF_8);
        Optional<Map<String, Object>> st = shop.publicStore(slug);
        if (st.isEmpty()) return "redirect:/";
        try {
            growth.rateStore(s.get().id(), ((Number) st.get().get("id")).longValue(), stars);
        } catch (IllegalArgumentException e) {
            return "redirect:/s/" + slug + "?rated=no#rating";
        }
        return "redirect:/s/" + slug + "?rated=1#rating";
    }

    @PostMapping("/s/{slug}/{product}/review")
    public String review(@PathVariable String slug, @PathVariable String product, @RequestParam(name = "rating", required = false) String rating,
                         @RequestParam(name = "comment", required = false) String comment, @CookieValue(name = Shoppers.COOKIE, required = false) String me,
                         Model model, HttpServletResponse res) {
        Optional<Shoppers.Shopper> s = shoppers.current(me);
        String back = "/s/" + slug + "/" + product;
        if (s.isEmpty()) return "redirect:/signin?next=" + URLEncoder.encode(back, StandardCharsets.UTF_8);
        Optional<Map<String, Object>> st = shop.publicStore(slug);
        if (st.isEmpty()) return "redirect:/";
        Optional<Map<String, Object>> p = shop.publicProduct(((Number) st.get().get("id")).longValue(), product);
        if (p.isEmpty()) return "redirect:/s/" + slug;
        long pid = ((Number) p.get().get("id")).longValue();
        Optional<Map<String, Object>> order = growth.reviewableOrder(s.get().id(), pid);
        if (order.isEmpty()) return "redirect:" + back + "#reviews";
        try {
            growth.review(order.get(), pid, rating, comment, s.get().firstName());
        } catch (IllegalArgumentException e) {
            model.addAttribute("reviewProblem", e.getMessage());
            return storefront.productPage(st.get(), p.get(), model, null);
        }
        return "redirect:" + back + "?reviewed=1#reviews";
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
