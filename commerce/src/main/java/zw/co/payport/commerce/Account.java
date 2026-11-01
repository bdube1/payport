package zw.co.payport.commerce;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The store user's side of the store: their own account, my orders (with reorder), saved addresses, favourite stores. */
@Controller
public class Account {

    @org.springframework.beans.factory.annotation.Autowired Chats chats;

    private final Shoppers shoppers;
    private final Settings settings;
    private final Payments payments;
    private final Carts carts;
    private final Shop shop;
    private final Growth growth;
    private final Storefront storefront;
    @org.springframework.beans.factory.annotation.Autowired Marketing marketing;

    public Account(Shoppers shoppers, Settings settings, Payments payments, Carts carts, Shop shop, Growth growth, Storefront storefront) {
        this.shoppers = shoppers;
        this.settings = settings;
        this.payments = payments;
        this.carts = carts;
        this.shop = shop;
        this.growth = growth;
        this.storefront = storefront;
    }

    static String safeNext(String next) {
        return next != null && next.matches("/[A-Za-z0-9/_.?=&%-]{0,200}") && !next.startsWith("//") ? next : "/me";
    }

    /** The Payport Store's own sign-in (store users are separate from Payport app customers). */
    @GetMapping("/signin")
    public String signInPage(@RequestParam(required = false) String next, Model model, HttpServletResponse res) {
        return auth("signin", next, null, null, model, res);
    }

    @PostMapping("/signin")
    public String signIn(@RequestParam(required = false) String who, @RequestParam(required = false) String password,
                         @RequestParam(required = false) String next, Model model, HttpServletResponse res) {
        try {
            shoppers.login(who, password, res);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return auth("signin", next, e.getMessage(), Map.of("who", who == null ? "" : who), model, res);
        }
        return "redirect:" + safeNext(next);
    }

    @GetMapping("/register")
    public String registerPage(@RequestParam(required = false) String next, Model model, HttpServletResponse res) {
        return auth("register", next, null, null, model, res);
    }

    @PostMapping("/register")
    public String register(@RequestParam(required = false) String name, @RequestParam(required = false) String email, @RequestParam(required = false) String phone,
                           @RequestParam(required = false) String password, @RequestParam(required = false) String next, Model model, HttpServletResponse res) {
        try {
            shoppers.register(name, email, phone, password, res);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return auth("register", next, e.getMessage(), Map.of("name", name == null ? "" : name, "email", email == null ? "" : email, "phone", phone == null ? "" : phone), model, res);
        }
        return "redirect:" + safeNext(next);
    }

    @GetMapping("/forgot")
    public String forgotPage(Model model, HttpServletResponse res) {
        return auth("forgot", null, null, null, model, res);
    }

    @PostMapping("/forgot")
    public String forgot(@RequestParam(required = false) String phone, Model model, HttpServletResponse res) {
        try {
            shoppers.sendReset(phone);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return auth("forgot", null, e.getMessage(), Map.of("phone", phone == null ? "" : phone), model, res);
        }
        return auth("reset", null, null, Map.of("phone", phone), model, res);
    }

    @PostMapping("/reset")
    public String reset(@RequestParam(required = false) String phone, @RequestParam(required = false) String code, @RequestParam(required = false) String password,
                        Model model, HttpServletResponse res) {
        try {
            shoppers.reset(phone, code, password, res);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return auth("reset", null, e.getMessage(), Map.of("phone", phone == null ? "" : phone), model, res);
        }
        return "redirect:/me";
    }

    String auth(String mode, String next, String problem, Map<String, String> values, Model model, HttpServletResponse res) {
        model.addAttribute("mode", mode);
        model.addAttribute("next", safeNext(next));
        model.addAttribute("problem", problem);
        Map<String, String> v = new LinkedHashMap<>(Map.of("who", "", "name", "", "email", "", "phone", ""));
        if (values != null) v.putAll(values);
        model.addAttribute("v", v);
        model.addAttribute("title", switch (mode) {
            case "register" -> "Create your account";
            case "forgot", "reset" -> "Reset your password";
            default -> "Sign in";
        } + " | Payport Store");
        res.setHeader("Cache-Control", "no-store");
        return "auth";
    }

    @PostMapping("/signout")
    public String signOut(@CookieValue(name = Shoppers.COOKIE, required = false) String me, HttpServletResponse res) {
        shoppers.signOut(me, res);
        return "redirect:/";
    }

    @GetMapping("/me")
    public String me(@CookieValue(name = Shoppers.COOKIE, required = false) String me, Model model, HttpServletResponse res) {
        Optional<Shoppers.Shopper> s = shoppers.current(me);
        if (s.isEmpty()) return "redirect:/signin?next=%2Fme";
        List<Map<String, Object>> orders = new ArrayList<>();
        for (Map<String, Object> o : shoppers.orders(s.get())) {
            Map<String, Object> v = new LinkedHashMap<>();
            String ref = String.valueOf(o.get("order_ref"));
            v.put("ref", ref);
            v.put("store", o.get("store_name"));
            v.put("storeUrl", "/s/" + o.get("store_slug"));
            v.put("summary", o.get("item_name"));
            v.put("total", Storefront.price(((Number) o.get("amount_minor")).longValue(), (String) o.get("currency")));
            v.put("date", Shop.time(o.get("created_at")).toLocalDate().toString());
            v.put("state", state(o));
            v.put("booking", growth.bookingOf(((Number) o.get("id")).longValue()).orElse(null));
            v.put("url", payments.orderUrl(ref, payments.viewToken(String.valueOf(o.get("idempotency_key")))).replace(settings.publicUrl, ""));
            v.put("reorder", o.get("store_id") != null);
            orders.add(v);
        }
        model.addAttribute("me", Map.of("name", s.get().name() == null ? "" : s.get().name(), "first", s.get().firstName(), "phone", s.get().phone() == null ? "" : s.get().phone(),
                "unread", chats.unreadForShopper(s.get().id())));
        model.addAttribute("orders", orders);
        model.addAttribute("addresses", shoppers.addresses(s.get()));
        model.addAttribute("favourites", shoppers.favourites(s.get()));
        model.addAttribute("title", "My account | Payport Store");
        res.setHeader("Cache-Control", "no-store");
        return "me";
    }

    static String state(Map<String, Object> o) {
        if ("CANCELLED".equals(o.get("order_status"))) return "Cancelled";
        String d = String.valueOf(o.get("delivery_status"));
        if ("READY".equals(d)) return "Ready to collect";
        if ("OUT".equals(d)) return "On its way";
        if ("DELIVERED".equals(d) || "COLLECTED".equals(d)) return "Done";
        return switch (String.valueOf(o.get("payment_status"))) {
            case "PAID" -> "Paid";
            case "ON_DELIVERY" -> "Pay on delivery";
            case "PENDING", "CONFIRMING" -> "Waiting for payment";
            case "FAILED", "EXPIRED" -> o.get("payment_reason") == null ? "Not paid" : "Not paid: " + o.get("payment_reason");
            default -> "Awaiting payment";
        };
    }

    /** Order the same again: the order's items (still on sale) go into that store's cart. */
    @PostMapping("/me/orders/{ref}/reorder")
    public String reorder(@PathVariable String ref, @CookieValue(name = Shoppers.COOKIE, required = false) String me,
                          @CookieValue(name = Carts.COOKIE, required = false) String cart, Model model, HttpServletResponse res) {
        Optional<Shoppers.Shopper> s = shoppers.current(me);
        if (s.isEmpty()) return "redirect:/signin?next=%2Fme";
        Optional<Map<String, Object>> o = shoppers.order(s.get(), ref);
        if (o.isEmpty() || o.get().get("store_id") == null) return storefront.missing(model, res, "This order does not exist");
        long storeId = ((Number) o.get().get("store_id")).longValue();
        String token = storefront.cartToken(cart, res);
        for (Map<String, Object> it : shop.jdbc().queryForList("SELECT product_id AS productId, quantity FROM order_items WHERE order_id = ?", o.get().get("id"))) {
            Object pid = it.get("productId");
            if (pid == null) continue;
            try {
                carts.set(storeId, token, ((Number) pid).longValue(), ((Number) it.get("quantity")).intValue(), true);
            } catch (IllegalArgumentException e) {
                // no longer on sale or not enough left: the cart shows what can be bought
            }
        }
        return "redirect:/s/" + o.get().get("store_slug") + "/cart";
    }

    /** The link at the end of every offer or reminder: stop them for that store. */
    @GetMapping("/u/{token}")
    public String stop(@PathVariable String token, Model model, HttpServletResponse res) {
        Optional<String> store = marketing.stop(token);
        if (store.isEmpty()) return storefront.missing(model, res, "This link does not work any more");
        model.addAttribute("store", store.get());
        res.setHeader("Cache-Control", "no-store");
        return "stopped";
    }

    @PostMapping("/me/addresses/{id}/delete")
    public String removeAddress(@PathVariable long id, @CookieValue(name = Shoppers.COOKIE, required = false) String me) {
        shoppers.current(me).ifPresent(s -> shoppers.removeAddress(s, id));
        return "redirect:/me";
    }

    @PostMapping("/s/{slug}/favourite")
    public String favourite(@PathVariable String slug, @CookieValue(name = Shoppers.COOKIE, required = false) String me) {
        Optional<Shoppers.Shopper> s = shoppers.current(me);
        if (s.isEmpty()) return "redirect:/signin?next=" + URLEncoder.encode("/s/" + slug, StandardCharsets.UTF_8);
        shop.publicStore(slug).ifPresent(st -> shoppers.toggleFavourite(s.get(), ((Number) st.get("id")).longValue()));
        return "redirect:/s/" + slug;
    }
}
