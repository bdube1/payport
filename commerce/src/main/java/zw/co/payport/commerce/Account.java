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

/** The shopper's side of the store: sign in with PayPort, my orders (with reorder), saved addresses, favourite stores. */
@Controller
public class Account {

    private final Shoppers shoppers;
    private final Settings settings;
    private final Payments payments;
    private final Carts carts;
    private final Shop shop;
    private final Growth growth;
    private final Storefront storefront;

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

    /** "Sign in with PayPort": the PayPort app signs the customer in and sends them back here with a one-time code. */
    @GetMapping("/signin")
    public String signIn(@RequestParam(required = false) String next) {
        return "redirect:" + settings.checkoutOrigin + "/store-signin?next=" + URLEncoder.encode(safeNext(next), StandardCharsets.UTF_8);
    }

    @GetMapping("/auth/callback")
    public String callback(@RequestParam(required = false) String code, @RequestParam(required = false) String next, Model model, HttpServletResponse res) {
        try {
            shoppers.signIn(code, res);
        } catch (Psp.Unavailable e) {
            return storefront.missing(model, res, "Signing in is not available at the moment. Please try again shortly.");
        } catch (RuntimeException e) {
            return "redirect:/signin?next=" + URLEncoder.encode(safeNext(next), StandardCharsets.UTF_8);
        }
        res.setHeader("Cache-Control", "no-store");
        return "redirect:" + safeNext(next);
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
        model.addAttribute("me", Map.of("name", s.get().name() == null ? "" : s.get().name(), "first", s.get().firstName(), "phone", s.get().phone() == null ? "" : s.get().phone()));
        model.addAttribute("orders", orders);
        model.addAttribute("addresses", shoppers.addresses(s.get()));
        model.addAttribute("favourites", shoppers.favourites(s.get()));
        model.addAttribute("title", "My account | PayPort Store");
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
            case "PENDING", "CONFIRMING" -> "Payment being confirmed";
            case "FAILED", "EXPIRED" -> "Not paid";
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
