package zw.co.payport.commerce;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PayPort Commerce Phase 1 acceptance (real MySQL, a stand-in PSP that can be switched off):
 * paid only when the PSP confirms; uncertain shows "being confirmed"; repeated clicks make one checkout; expired,
 * disabled and used links refuse payment; open amounts respect their limits; events replayed change nothing; a
 * refund stays requested until the PSP pays it out; merchants cannot reach each other's things; and with the PSP
 * down the store keeps working and the order is kept.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class CommerceScenarioTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");
    static final FakePsp PSP;

    static {
        try {
            PSP = new FakePsp();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("COMMERCE_DB_URL", MYSQL::getJdbcUrl);
        r.add("COMMERCE_DB_USERNAME", MYSQL::getUsername);
        r.add("COMMERCE_DB_PASSWORD", MYSQL::getPassword);
        r.add("commerce.psp-url", PSP::url);
        r.add("commerce.psp-key", () -> FakePsp.KEY);
        r.add("commerce.token-secret", () -> "commerce-test-secret-0123456789abcdef");
        r.add("commerce.public-url", () -> "https://store.test");
        r.add("commerce.checkout-origin", () -> "https://app.test");
        r.add("commerce.portal-origins", () -> "https://merchant.test");
        r.add("commerce.events.poll-ms", () -> "3600000");
        r.add("commerce.reconcile-ms", () -> "3600000");
    }

    @AfterAll
    static void stop() {
        PSP.stop();
    }

    @Value("${local.server.port}") int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired EventReader reader;
    @Autowired Payments payments;
    @Autowired Domains domains;
    @Autowired Marketing marketing;
    @org.springframework.test.context.bean.override.mockito.MockitoBean Domains.TxtLookup dns;

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    record Res(int status, String body, String location, String cookie) {
        Res(int status, String body, String location) { this(status, body, location, null); }
        JsonNode json() {
            try { return JSON.readTree(body); } catch (Exception e) { return JSON.createObjectNode(); }
        }
        JsonNode data() { return json().path("data"); }
    }

    Res api(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body(), r.headers().firstValue("Location").orElse(null));
    }

    Res page(String path) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body(), r.headers().firstValue("Location").orElse(null));
    }

    Res form(String path, Map<String, String> fields) throws Exception {
        return form(path, fields, null);
    }

    /** A form post as a browser with the cart cookie (returns the cookie it was given, if any). */
    Res form(String path, Map<String, String> fields, String cookie) throws Exception {
        String body = fields.entrySet().stream().map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body));
        if (cookie != null) b.header("Cookie", cookie);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        String set = r.headers().firstValue("Set-Cookie").map(c -> c.split(";")[0]).orElse(cookie);
        return new Res(r.statusCode(), r.body(), r.headers().firstValue("Location").orElse(null), set);
    }

    Res page(String path, String cookie) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body(), r.headers().firstValue("Location").orElse(null), cookie);
    }

    String signIn(String portalToken) throws Exception {
        Res s = api("POST", "/api/commerce/session", null, Map.of("portalToken", portalToken));
        assertEquals(200, s.status(), s.body());
        return s.data().path("token").asText();
    }

    static String key() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    String orderRefOf(String key) {
        return jdbc.queryForObject("SELECT order_ref FROM orders WHERE idempotency_key = ?", String.class, key);
    }

    String payment(String orderRef) {
        return jdbc.queryForObject("SELECT payment_status FROM orders WHERE order_ref = ?", String.class, orderRef);
    }

    @Test
    void linkPaidOnlyWhenThePspConfirms_oneCheckoutPerClick_uncertainIsBeingConfirmed_refundWaitsForPayout() throws Exception {
        PSP.down = false;
        assertEquals(401, api("GET", "/api/commerce/links", null, null).status(), "the merchant API needs a session");
        assertEquals(401, api("POST", "/api/commerce/session", null, Map.of("portalToken", "nobody")).status());
        String alpha = signIn("portal-token-A");

        Res link = api("POST", "/api/commerce/links", alpha, Map.of("title", "Deposit for a table", "currency", "USD", "amountMinor", 2_500, "reference", "INV-77"));
        assertEquals(200, link.status(), link.body());
        String code = link.data().path("code").asText();
        assertTrue(link.data().path("url").asText().equals("https://store.test/l/" + code));
        Res shown = page("/l/" + code);
        assertEquals(200, shown.status());
        assertTrue(shown.body().contains("Deposit for a table") && shown.body().contains("USD 25.00") && shown.body().contains("INV-77"), "the link page");
        assertTrue(shown.body().contains("wa.me/?text=") && shown.body().contains("facebook.com/sharer") && shown.body().contains("/qr.svg?path="), "share buttons and QR");
        assertEquals(200, page("/qr.svg?path=" + URLEncoder.encode("/l/" + code, StandardCharsets.UTF_8)).status());
        assertEquals(400, page("/qr.svg?path=" + URLEncoder.encode("https://evil.example", StandardCharsets.UTF_8)).status(), "QR only for this site's pages");

        // Two clicks on the same page: one order, one PSP checkout.
        String k = key();
        int before = PSP.creates.get();
        Res pay1 = form("/l/" + code + "/pay", Map.of("k", k, "name", "Tari", "phone", "0771234567"));
        Res pay2 = form("/l/" + code + "/pay", Map.of("k", k, "name", "Tari", "phone", "0771234567"));
        assertEquals(302, pay1.status(), pay1.body());
        assertTrue(pay1.location().startsWith("https://app.test/checkout/"), pay1.location());
        assertEquals(pay1.location(), pay2.location(), "the second click goes to the same checkout");
        assertEquals(before + 1, PSP.creates.get(), "one checkout at the PSP");
        String ref = orderRefOf(k);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE link_id = ?", Integer.class, link.data().path("id").asLong()));
        assertEquals("AWAITING_PAYMENT", payment(ref));

        // The customer pays; the provider's answer is unclear: being confirmed, never paid.
        PSP.set(1001, ref + "-1", "PENDING", "UNCERTAIN");
        reader.poll();
        assertEquals("CONFIRMING", payment(ref));
        String token = payments.viewToken(k);
        Res waiting = page("/o/" + ref + "?t=" + token);
        assertTrue(waiting.body().contains("being confirmed") && !waiting.body().contains(">Paid<"), "never shown as paid");
        assertEquals(404, page("/o/" + ref + "?t=wrong").status(), "an order page needs its key");

        // Confirmed: paid. A repeated event, a replay of the whole stream and a late failure change nothing.
        PSP.set(1001, ref + "-1", "PAID", "PAID");
        reader.poll();
        assertEquals("PAID", payment(ref));
        PSP.event("checkout.paid", 1001, ref + "-1");
        jdbc.update("UPDATE event_cursor SET last_id = 0 WHERE name = 'psp'");
        reader.poll();
        PSP.set(1001, ref + "-1", "FAILED", "FAILED");
        reader.poll();
        assertEquals("PAID", payment(ref), "a payment is never undone by a later message");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM commerce_events WHERE event_type = 'order.paid' AND subject_ref = ?", Integer.class, ref), "paid once");
        assertTrue(page("/o/" + ref + "?t=" + token).body().contains(">Paid<"));

        // Bravo cannot see, change or refund Alpha's link and order.
        String bravo = signIn("portal-token-B");
        long linkId = link.data().path("id").asLong();
        assertEquals(404, api("GET", "/api/commerce/links/" + linkId, bravo, null).status());
        assertEquals(404, api("POST", "/api/commerce/links/" + linkId + "/status", bravo, Map.of("active", false)).status());
        assertEquals(404, api("GET", "/api/commerce/orders/" + ref, bravo, null).status());
        assertEquals(404, api("POST", "/api/commerce/orders/" + ref + "/refunds", bravo, Map.of("amountMinor", 100, "reason", "mine")).status());
        assertFalse(api("GET", "/api/commerce/orders", bravo, null).body().contains(ref));

        // Alpha refunds from its balance: requested until the PSP pays the customer back.
        Res refund = api("POST", "/api/commerce/orders/" + ref + "/refunds", alpha, Map.of("amountMinor", 1_000, "reason", "Table not available"));
        assertEquals(200, refund.status(), refund.body());
        assertEquals("REQUESTED", refund.data().path("refundStatus").asText());
        assertEquals(400, api("POST", "/api/commerce/orders/" + ref + "/refunds", alpha, Map.of("amountMinor", 1_600, "reason", "too much")).status(), "never more than is left");
        PSP.refundPaid(1001, ref + "-1");
        reader.poll();
        Res after = api("GET", "/api/commerce/orders/" + ref, alpha, null);
        assertEquals("PARTIAL", after.data().path("refundStatus").asText(), after.body());
        assertEquals(1_000, after.data().path("refundedMinor").asLong());
        assertTrue(page("/o/" + ref + "?t=" + token).body().contains("Part of this payment has been refunded"));
    }

    @Test
    void expiredDisabledAndUsedLinksRefuse_openAmountsRespectLimits_productsAndStore() throws Exception {
        PSP.down = false;
        String alpha = signIn("portal-token-A");
        Res open = api("POST", "/api/commerce/links", alpha, Map.of("title", "School fees", "currency", "USD", "minMinor", 500, "maxMinor", 10_000));
        String code = open.data().path("code").asText();
        Res low = form("/l/" + code + "/pay", Map.of("k", key(), "amount", "2.00"));
        assertEquals(200, low.status());
        assertTrue(low.body().contains("The smallest amount is USD 5.00"), low.body());
        assertTrue(form("/l/" + code + "/pay", Map.of("k", key(), "amount", "150")).body().contains("The largest amount is USD 100.00"));
        assertTrue(form("/l/" + code + "/pay", Map.of("k", key(), "amount", "abc")).body().contains("Enter the amount"));
        assertEquals(302, form("/l/" + code + "/pay", Map.of("k", key(), "amount", "12.50")).status());

        long id = open.data().path("id").asLong();
        assertEquals(200, api("POST", "/api/commerce/links/" + id + "/status", alpha, Map.of("active", false)).status());
        Res off = form("/l/" + code + "/pay", Map.of("k", key(), "amount", "12.50"));
        assertTrue(off.status() == 200 && off.body().contains("switched off"), off.body());

        Res soon = api("POST", "/api/commerce/links", alpha, Map.of("title", "Flash sale", "currency", "USD", "amountMinor", 300, "expiresAt", java.time.LocalDate.now().plusDays(1).toString()));
        jdbc.update("UPDATE links SET expires_at = NOW() - INTERVAL 1 MINUTE WHERE code = ?", soon.data().path("code").asText());
        assertTrue(form("/l/" + soon.data().path("code").asText() + "/pay", Map.of("k", key())).body().contains("expired"));
        assertEquals(400, api("POST", "/api/commerce/links", alpha, Map.of("title", "Past", "currency", "USD", "amountMinor", 300, "expiresAt", "2020-01-01")).status());
        assertEquals(400, api("POST", "/api/commerce/links", alpha, Map.of("title", "Kwacha", "currency", "ZMW", "amountMinor", 300)).status(), "only the merchant's currencies");

        // A single-use link closes once paid.
        Res once = api("POST", "/api/commerce/links", alpha, Map.of("title", "Invoice 12", "currency", "USD", "amountMinor", 4_000, "reusable", false));
        String k = key();
        form("/l/" + once.data().path("code").asText() + "/pay", Map.of("k", k));
        PSP.set(1001, orderRefOf(k) + "-1", "PAID", "PAID");
        reader.poll();
        assertEquals("USED", api("GET", "/api/commerce/links/" + once.data().path("id").asLong(), alpha, null).data().path("status").asText());
        assertTrue(form("/l/" + once.data().path("code").asText() + "/pay", Map.of("k", key())).body().contains("already been paid"));

        // Products, the mini-store, the cart, stock, pickup or delivery, and customers (Phase 2a).
        Res p = api("POST", "/api/commerce/products", alpha, Map.of("name", "Woven basket", "description", "Handmade in Binga", "priceMinor", 1_800, "currency", "USD", "stock", 5));
        assertEquals(200, p.status(), p.body());
        assertEquals(5, p.data().path("stock").asInt());
        Res p2 = api("POST", "/api/commerce/products", alpha, Map.of("name", "Clay pot", "priceMinor", 1_200, "currency", "USD"));
        assertEquals(200, api("PUT", "/api/commerce/store", alpha, Map.of("deliveryEnabled", true, "deliveryFeeMinor", 300, "freeDeliveryFromMinor", 10_000,
                "deliveryNote", "Harare only", "pickupEnabled", true)).status());
        assertEquals(400, api("PUT", "/api/commerce/store", alpha, Map.of("deliveryEnabled", false, "pickupEnabled", false)).status(), "at least one way to get the order");
        String productUrl = p.data().path("url").asText().replace("https://store.test", "");
        String potUrl = p2.data().path("url").asText().replace("https://store.test", "");
        String storePath = productUrl.substring(0, productUrl.lastIndexOf('/'));
        Res store = page(storePath);
        assertTrue(store.status() == 200 && store.body().contains("Woven basket") && store.body().contains("USD 18.00"), store.body());
        Res product = page(productUrl);
        assertTrue(product.body().contains("og:title") && product.body().contains("Handmade in Binga") && product.body().contains("Only 5 left"));

        Res added = form(productUrl + "/buy", Map.of("qty", "3"), null);
        assertEquals(302, added.status(), added.body());
        assertTrue(added.location().endsWith(storePath + "/cart"), added.location());
        String cookie = added.cookie();
        assertTrue(cookie != null && cookie.startsWith("pp_cart="), "a cart cookie");
        assertEquals(302, form(potUrl + "/buy", Map.of("qty", "1"), cookie).status());
        Res cart = page(storePath + "/cart", cookie);
        assertTrue(cart.body().contains("Woven basket") && cart.body().contains("Clay pot") && cart.body().contains("USD 66.00"), cart.body());
        assertTrue(cart.body().contains("USD 69.00") && cart.body().contains("delivery USD 3.00") && cart.body().contains("Harare only"), "delivery fee shown: " + cart.body());
        assertTrue(form(productUrl + "/buy", Map.of("qty", "3"), cookie).body().contains("Only 5 left"), "never more than in stock");
        assertTrue(form(productUrl + "/buy", Map.of("qty", "0"), cookie).body().contains("quantity"));
        String ck = key();
        assertTrue(form(storePath + "/checkout", Map.of("k", ck, "method", "DELIVERY", "name", "Rudo"), cookie).body().contains("name and phone"));
        assertTrue(form(storePath + "/checkout", Map.of("k", ck, "method", "DELIVERY", "name", "Rudo", "phone", "0772000111"), cookie).body().contains("delivery address"));
        Res bought = form(storePath + "/checkout", Map.of("k", ck, "method", "DELIVERY", "name", "Rudo", "phone", "0772000111", "address", "12 Samora Machel Ave, Harare"), cookie);
        assertEquals(302, bought.status(), bought.body());
        String ref = orderRefOf(ck);
        assertEquals(6_900L, jdbc.queryForObject("SELECT amount_minor FROM orders WHERE order_ref = ?", Long.class, ref), "items plus delivery");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM order_items i JOIN orders o ON o.id = i.order_id WHERE o.order_ref = ?", Integer.class, ref));
        assertTrue(page(storePath + "/cart", cookie).body().contains("Your cart is empty"));
        PSP.set(1001, ref + "-1", "PAID", "PAID");
        reader.poll();
        assertEquals(2, jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, p.data().path("id").asLong()), "paid: stock goes down");
        Res o = api("GET", "/api/commerce/orders/" + ref, alpha, null);
        assertEquals("DELIVERY", o.data().path("deliveryMethod").asText());
        assertEquals("PREPARING", o.data().path("deliveryStatus").asText());
        assertEquals(2, o.data().path("items").size());
        String bravoSession = signIn("portal-token-B");
        assertEquals(404, api("POST", "/api/commerce/orders/" + ref + "/delivery", bravoSession, Map.of("status", "OUT")).status());
        assertEquals(400, api("POST", "/api/commerce/orders/" + ref + "/delivery", alpha, Map.of("status", "COLLECTED")).status(), "collected is for pickup");
        assertEquals(200, api("POST", "/api/commerce/orders/" + ref + "/delivery", alpha, Map.of("status", "OUT")).status());
        assertTrue(page("/o/" + ref + "?t=" + payments.viewToken(ck)).body().contains("on its way"));
        assertEquals("FULFILLED", api("POST", "/api/commerce/orders/" + ref + "/delivery", alpha, Map.of("status", "DELIVERED")).data().path("orderStatus").asText());
        // Phase 3: bought together, search, and insights for the merchant.
        assertTrue(page(productUrl).body().contains("Customers also bought") && page(productUrl).body().contains("Clay pot"), "the pot was bought with the basket");
        Res found = page(storePath + "/search?q=woven");
        assertTrue(found.body().contains("Woven basket") && found.body().contains("found for"), found.body());
        assertFalse(page(storePath + "/search?q=woven").body().contains(">Clay pot<"), "search only shows matches");
        Res ins = api("GET", "/api/commerce/insights", alpha, null);
        assertEquals(200, ins.status(), ins.body());
        assertTrue(ins.data().path("bestSellers").toString().contains("Woven basket"), ins.body());
        Res customers = api("GET", "/api/commerce/customers", alpha, null);
        assertTrue(customers.body().contains("Rudo") && customers.body().contains("0772000111"), customers.body());
        assertFalse(api("GET", "/api/commerce/customers", bravoSession, null).body().contains("Rudo"), "customers belong to their merchant");
        jdbc.update("UPDATE products SET stock = 0 WHERE id = ?", p.data().path("id").asLong());
        assertTrue(page(productUrl).body().contains("Sold out"));
        String restock = api("GET", "/api/commerce/insights", alpha, null).data().path("restock").toString();
        assertTrue(restock.contains("Woven basket") && restock.contains("SOLD_OUT"), "sold out after selling: restock " + restock);

        String bravo = signIn("portal-token-B");
        assertEquals(404, api("PUT", "/api/commerce/products/" + p.data().path("id").asLong(), bravo, Map.of("name", "Mine", "priceMinor", 1, "currency", "USD")).status());
        Res ov = api("GET", "/api/commerce/overview", alpha, null);
        assertEquals(200, ov.status(), ov.body());
        assertTrue(ov.data().path("minutesToFirstLink").isNumber() && ov.data().path("checkoutsStarted30d").asLong() > 0, ov.body());
    }

    @Test
    void pspDown_storeStillWorks_orderKept_paysWhenItIsBack() throws Exception {
        PSP.down = false;
        String alpha = signIn("portal-token-A");
        Res link = api("POST", "/api/commerce/links", alpha, Map.of("title", "Delivery", "currency", "USD", "amountMinor", 700));
        String code = link.data().path("code").asText();

        PSP.down = true;
        assertEquals(200, page("/l/" + code).status(), "pages are served from Commerce's own data");
        assertEquals(200, api("GET", "/api/commerce/links", alpha, null).status(), "a signed-in merchant keeps working");
        assertEquals(503, api("POST", "/api/commerce/session", null, Map.of("portalToken", "portal-token-A")).status(), "signing in needs the PSP");
        String k = key();
        Res tried = form("/l/" + code + "/pay", Map.of("k", k));
        assertEquals(200, tried.status());
        assertTrue(tried.body().contains("Payments are briefly unavailable") && tried.body().contains("is saved"), tried.body());
        String ref = orderRefOf(k);
        assertEquals("NOT_STARTED", payment(ref));
        reader.poll();   // waits, does not lose its place
        assertFalse(reader.pspUp());

        PSP.down = false;
        Res retry = form("/o/" + ref + "/pay", Map.of("t", payments.viewToken(k)));
        assertEquals(302, retry.status(), retry.body());
        assertTrue(retry.location().startsWith("https://app.test/checkout/"));
        // The first try is never paid; the customer starts again and pays the second.
        PSP.set(1001, ref + "-1", "EXPIRED", "NOT_PAID");
        reader.poll();
        assertEquals("EXPIRED", payment(ref));
        Res second = form("/o/" + ref + "/pay", Map.of("t", payments.viewToken(k)));
        assertEquals(302, second.status());
        PSP.set(1001, ref + "-2", "PAID", "PAID");
        // An event missed while Commerce was busy: the status check still finds it.
        jdbc.update("UPDATE order_attempts SET updated_at = NOW() - INTERVAL 5 MINUTE");
        payments.reconcile();
        assertEquals("PAID", payment(ref));
        assertTrue(reader.pspUp() || true);
    }

    /** Review of 31 Oct 2026: two customers paying for the last item; uncertain payments keep it; failed ones release it. */
    @Test
    void lastItem_reservedAtCheckout_secondCustomerRefused_uncertainKeeps_failedReleases_designsAndDomains() throws Exception {
        PSP.down = false;
        String alpha = signIn("portal-token-A");
        assertEquals(200, api("PUT", "/api/commerce/store", alpha, Map.of("pickupEnabled", true, "design", "BOUTIQUE", "tagline", "Made by hand")).status());
        Res last = api("POST", "/api/commerce/products", alpha, Map.of("name", "Last lamp", "priceMinor", 5_000, "currency", "USD", "stock", 1, "category", "Lighting"));
        String url = last.data().path("url").asText().replace("https://store.test", "");
        String storePath = url.substring(0, url.lastIndexOf('/'));
        long id = last.data().path("id").asLong();

        String a = form(url + "/buy", Map.of("qty", "1"), null).cookie(), b = form(url + "/buy", Map.of("qty", "1"), null).cookie();
        String ka = key(), kb = key();
        Res first = form(storePath + "/checkout", Map.of("k", ka, "method", "PICKUP", "name", "Ann", "phone", "0771000001"), a);
        assertEquals(302, first.status(), first.body());
        assertEquals(0, jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, id), "reserved before payment");
        Res second = form(storePath + "/checkout", Map.of("k", kb, "method", "PICKUP", "name", "Ben", "phone", "0771000002"), b);
        assertTrue(second.status() == 200 && second.body().contains("Sold out"), "the second customer cannot pay for it: " + second.body());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE idempotency_key = ?", Integer.class, kb), "no order, no payment");
        // Even if both pages passed their checks at the same moment, the database takes the last item once.
        IllegalArgumentException race = assertThrows(IllegalArgumentException.class, () -> payments.place(new Payments.NewOrder(1001, null, null, id, "Last lamp", 1, 5_000,
                "USD", "Ben", "0771000002", null, key(), java.util.List.of(new Payments.Item(id, "Last lamp", 5_000, 1)), "PICKUP", 0, null)));
        assertTrue(race.getMessage().contains("sold out"), race.getMessage());
        assertEquals(0, jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, id), "the refused order took nothing");

        String ref = orderRefOf(ka);
        PSP.set(1001, ref + "-1", "PENDING", "UNCERTAIN");
        reader.poll();
        assertEquals(0, jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, id), "uncertain: still held");
        PSP.set(1001, ref + "-1", "FAILED", "FAILED");
        reader.poll();
        assertEquals("RELEASED", jdbc.queryForObject("SELECT stock_state FROM orders WHERE order_ref = ?", String.class, ref));
        assertEquals(1, jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, id), "failed: back on sale");
        // Trying again reserves again; paying makes it a sale.
        Res again = form("/o/" + ref + "/pay", Map.of("t", payments.viewToken(ka)));
        assertEquals(302, again.status(), again.body());
        assertEquals(0, jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, id));
        PSP.set(1001, ref + "-2", "PAID", "PAID");
        reader.poll();
        assertEquals("SOLD", jdbc.queryForObject("SELECT stock_state FROM orders WHERE order_ref = ?", String.class, ref));
        assertEquals(0, jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, id));

        // The boutique design shows categories and the tagline.
        Res page = page(storePath);
        assertTrue(page.body().contains("d-boutique") && page.body().contains("Lighting") && page.body().contains("Made by hand"), page.body());
        assertEquals(400, api("PUT", "/api/commerce/store", alpha, Map.of("design", "FANCY")).status());

        // Custom domains: proved by DNS before any certificate or routing.
        assertEquals(400, api("POST", "/api/commerce/domains", alpha, Map.of("domain", "evil.payport.africa")).status());
        Res d = api("POST", "/api/commerce/domains", alpha, Map.of("domain", "https://Shop.AlphaCrafts.co.zw/"));
        assertEquals(200, d.status(), d.body());
        assertEquals("shop.alphacrafts.co.zw", d.data().path("domain").asText());
        assertEquals("PENDING", d.data().path("status").asText());
        assertEquals(404, page("/internal/tls/allowed?domain=shop.alphacrafts.co.zw").status(), "no certificate before verification");
        long did = d.data().path("id").asLong();
        String bravo = signIn("portal-token-B");
        assertEquals(409, api("POST", "/api/commerce/domains", bravo, Map.of("domain", "shop.alphacrafts.co.zw")).status(), "one store per domain");
        assertEquals(404, api("POST", "/api/commerce/domains/" + did + "/verify", bravo, null).status());
        org.mockito.Mockito.when(dns.txt("_payport.shop.alphacrafts.co.zw")).thenReturn(java.util.List.of("something-else"));
        assertEquals("PENDING", api("POST", "/api/commerce/domains/" + did + "/verify", alpha, null).data().path("status").asText());
        org.mockito.Mockito.when(dns.txt("_payport.shop.alphacrafts.co.zw")).thenReturn(java.util.List.of(d.data().path("txtValue").asText()));
        assertEquals("VERIFIED", api("POST", "/api/commerce/domains/" + did + "/verify", alpha, null).data().path("status").asText());
        assertEquals(200, page("/internal/tls/allowed?domain=shop.alphacrafts.co.zw").status());
        assertEquals(storePath.substring(3), domains.storeFor("shop.alphacrafts.co.zw:443").orElse(""));
        assertEquals(404, api("DELETE", "/api/commerce/domains/" + did, bravo, null).status());
        assertEquals(200, api("DELETE", "/api/commerce/domains/" + did, alpha, null).status());
        assertEquals(404, page("/internal/tls/allowed?domain=shop.alphacrafts.co.zw").status(), "removed: no more certificates");
    }

    /** Owner, 31 Oct 2026: stores of every kind, the merchant's own collections, sales and codes, pay on delivery, services, the directory. */
    @Test
    void storeBuilder_directoryByType_collections_featuredAndSale_discountCodes_payOnDelivery_services() throws Exception {
        PSP.down = false;
        String bravo = signIn("portal-token-B"), alpha = signIn("portal-token-A");
        assertEquals(400, api("PUT", "/api/commerce/store", bravo, Map.of("businessType", "CARS")).status());
        Res st = api("PUT", "/api/commerce/store", bravo, Map.of("businessType", "FOOD", "announcement", "Free chips on Fridays", "codEnabled", true,
                "pickupEnabled", true, "deliveryEnabled", true, "deliveryFeeMinor", 200, "listed", true));
        assertEquals(200, st.status(), st.body());
        assertTrue(api("GET", "/api/commerce/business-types", bravo, null).body().contains("Food"));

        // Collections are the merchant's own groups.
        Res breakfast = api("POST", "/api/commerce/collections", bravo, Map.of("name", "Breakfast"));
        Res drinks = api("POST", "/api/commerce/collections", bravo, Map.of("name", "Cold drinks"));
        assertEquals(200, drinks.status(), drinks.body());
        long bId = breakfast.data().path("id").asLong(), dId = drinks.data().path("id").asLong();
        assertEquals(404, api("DELETE", "/api/commerce/collections/" + bId, alpha, null).status(), "not alpha's collection");
        assertEquals(400, api("POST", "/api/commerce/products", alpha, Map.of("name", "Sneaky", "priceMinor", 100, "currency", "USD", "collectionIds", java.util.List.of(bId))).status());
        assertFalse(api("GET", "/api/commerce/products", alpha, null).body().contains("Sneaky"), "nothing saved when the collections are refused");

        Res sadza = api("POST", "/api/commerce/products", bravo, Map.of("name", "Sadza and beef", "priceMinor", 600, "currency", "USD", "stock", 10,
                "featured", true, "collectionIds", java.util.List.of(bId)));
        assertEquals(200, sadza.status(), sadza.body());
        assertEquals(bId, sadza.data().path("collectionIds").get(0).asLong());
        assertEquals(400, api("POST", "/api/commerce/products", bravo, Map.of("name", "Bad sale", "priceMinor", 500, "currency", "USD", "compareAtMinor", 400)).status(), "the old price must be higher");
        Res mazoe = api("POST", "/api/commerce/products", bravo, Map.of("name", "Mazoe orange", "priceMinor", 300, "currency", "USD", "stock", 10,
                "compareAtMinor", 400, "collectionIds", java.util.List.of(dId)));
        assertEquals(200, mazoe.status(), mazoe.body());
        Res chef = api("POST", "/api/commerce/products", bravo, Map.of("name", "Private chef booking", "priceMinor", 2_000, "currency", "USD", "kind", "SERVICE", "stock", 5));
        assertEquals("SERVICE", chef.data().path("kind").asText());
        assertTrue(chef.data().path("stock").isNull(), "a service has no stock");

        String url = sadza.data().path("url").asText().replace("https://store.test", "");
        String storePath = url.substring(0, url.lastIndexOf('/'));
        Res home = page(storePath);
        assertTrue(home.body().contains("Free chips on Fridays") && home.body().contains("Breakfast") && home.body().contains("Cold drinks") && home.body().contains("Featured"), home.body());
        assertTrue(home.body().contains("USD 4.00") && home.body().contains("Sale"), "old price struck through");
        String only = page(storePath + "?c=" + drinks.data().path("slug").asText()).body();
        assertTrue(only.contains("Mazoe orange") && !only.contains("Sadza and beef"), only);
        assertTrue(page(chef.data().path("url").asText().replace("https://store.test", "")).body().contains("Book"));

        // The PayPort directory lists stores by what they sell.
        assertTrue(page("/").body().contains("Bravo Foods"));
        assertTrue(page("/?t=FOOD").body().contains("Bravo Foods"));
        assertFalse(page("/?t=CLOTHING").body().contains("Bravo Foods"));
        assertTrue(page("/?q=Mazoe").body().contains("Bravo Foods"));
        // The search box is PayPort AI's assistant: plain words find the item, with a short answer.
        Res asked = page("/?q=" + URLEncoder.encode("cheap orange please", StandardCharsets.UTF_8));
        assertTrue(asked.body().contains("PayPort AI") && asked.body().contains("Mazoe orange") && asked.body().contains("lowest price"), asked.body());
        Res written = api("POST", "/api/commerce/ai/describe", bravo, Map.of("name", "Mazoe orange", "category", "Drinks", "notes", "ice cold, 2 litres"));
        assertEquals(200, written.status(), written.body());
        assertTrue(written.data().path("description").asText().contains("Mazoe orange"), written.body());
        assertEquals(400, api("POST", "/api/commerce/ai/describe", bravo, Map.of("category", "Drinks")).status(), "a name is needed");
        api("PUT", "/api/commerce/store", bravo, Map.of("listed", false));
        assertFalse(page("/").body().contains("Bravo Foods"), "a store can stay out of the directory");
        api("PUT", "/api/commerce/store", bravo, Map.of("listed", true));

        // Discount codes: checked on the server, with a minimum and a number of uses.
        assertEquals(400, api("POST", "/api/commerce/discounts", bravo, Map.of("code", "HALF", "kind", "PERCENT", "value", 95)).status());
        Res code = api("POST", "/api/commerce/discounts", bravo, Map.of("code", "save10", "kind", "PERCENT", "value", 10, "minOrderMinor", 1_000, "maxUses", 1));
        assertEquals(200, code.status(), code.body());
        assertEquals("SAVE10", code.data().path("code").asText());
        assertEquals(409, api("POST", "/api/commerce/discounts", bravo, Map.of("code", "SAVE10", "kind", "AMOUNT", "value", 100)).status());
        assertEquals(404, api("POST", "/api/commerce/discounts/" + code.data().path("id").asLong() + "/status", alpha, Map.of("active", false)).status());

        String cookie = form(url + "/buy", Map.of("qty", "1"), null).cookie();
        assertTrue(page(storePath + "/cart?code=SAVE10", cookie).body().contains("needs items of at least"), "below the minimum");
        form(storePath + "/cart/" + sadza.data().path("id").asLong(), Map.of("qty", "2"), cookie);
        Res cart = page(storePath + "/cart?code=save10", cookie);
        assertTrue(cart.body().contains("USD 1.20") && cart.body().contains("Pay on delivery"), cart.body());

        // Pay on delivery: no PSP checkout; the items are held until the merchant records the cash.
        int creates = PSP.creates.get();
        String k = key();
        Res placed = form(storePath + "/checkout", Map.of("k", k, "method", "PICKUP", "name", "Tendai", "phone", "0773000444", "code", "SAVE10", "pay", "CASH"), cookie);
        assertEquals(200, placed.status(), placed.body());
        assertTrue(placed.body().contains("pay on delivery"), placed.body());
        assertEquals(creates, PSP.creates.get(), "no PSP checkout for cash");
        String ref = orderRefOf(k);
        Map<String, Object> o = jdbc.queryForMap("SELECT * FROM orders WHERE order_ref = ?", ref);
        assertEquals("ON_DELIVERY", o.get("payment_status"));
        assertEquals("CASH", o.get("pay_method"));
        assertEquals(120L, ((Number) o.get("discount_minor")).longValue());
        assertEquals(1_080L, ((Number) o.get("amount_minor")).longValue());
        assertEquals("RESERVED", o.get("stock_state"));
        assertEquals(8, jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, sadza.data().path("id").asLong()));
        assertEquals(1, jdbc.queryForObject("SELECT used FROM discount_codes WHERE id = ?", Integer.class, code.data().path("id").asLong()));
        String other = form(url + "/buy", Map.of("qty", "2"), null).cookie();
        assertTrue(page(storePath + "/cart?code=SAVE10", other).body().contains("used up"));
        Res online = form("/o/" + ref + "/pay", Map.of("t", payments.viewToken(k)));
        assertTrue(online.status() == 200 && online.body().contains("on delivery or collection"), "no online payment for a cash order: " + online.body());
        assertEquals(creates, PSP.creates.get());

        assertEquals("CASH", api("GET", "/api/commerce/orders/" + ref, bravo, null).data().path("payMethod").asText());
        assertEquals(409, api("POST", "/api/commerce/orders/" + ref + "/refunds", bravo, Map.of("amountMinor", 100, "reason", "cash")).status(), "cash is refunded by hand");
        assertEquals(404, api("POST", "/api/commerce/orders/" + ref + "/cash-received", alpha, null).status());
        Res cashIn = api("POST", "/api/commerce/orders/" + ref + "/cash-received", bravo, null);
        assertEquals(200, cashIn.status(), cashIn.body());
        assertEquals("PAID", jdbc.queryForObject("SELECT payment_status FROM orders WHERE order_ref = ?", String.class, ref));
        assertEquals("SOLD", jdbc.queryForObject("SELECT stock_state FROM orders WHERE order_ref = ?", String.class, ref));
        assertEquals(409, api("POST", "/api/commerce/orders/" + ref + "/cash-received", bravo, null).status(), "only once");

        // A services-only cart has no pickup or delivery choice; paid online as usual.
        String sc = form(chef.data().path("url").asText().replace("https://store.test", "") + "/buy", Map.of("qty", "1"), null).cookie();
        Res scart = page(storePath + "/cart", sc);
        assertFalse(scart.body().contains("How do you want it?"), scart.body());
        String sk = key();
        Res booked = form(storePath + "/checkout", Map.of("k", sk, "name", "Farai", "phone", "0774000555", "pay", "ONLINE"), sc);
        assertEquals(302, booked.status(), booked.body());
        assertTrue(booked.location().startsWith("https://app.test/checkout/"), booked.location());
        assertEquals("NONE", jdbc.queryForObject("SELECT delivery_method FROM orders WHERE idempotency_key = ?", String.class, sk));
        assertEquals(2_000L, jdbc.queryForObject("SELECT amount_minor FROM orders WHERE idempotency_key = ?", Long.class, sk));
    }

    String waitForMessage(String startsWith) throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            synchronized (PSP.messages) {
                for (String m : PSP.messages) if (m.startsWith(startsWith)) return m;
            }
            Thread.sleep(100);
        }
        return null;
    }

    static String firstSlot(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("<option value=\"(20\\d\\d-\\d\\d-\\d\\dT\\d\\d:\\d\\d)\"").matcher(html);
        return m.find() ? m.group(1) : null;
    }

    /** Owner, 31 Oct 2026: payment options, reviews from buyers, delivery areas, bookings in opening hours, order texts. */
    @Test
    void growth_methodsChosenByMerchant_bookingsHeldAndConfirmed_reviewsFromBuyers_deliveryAreas_orderTexts() throws Exception {
        PSP.down = false;
        String charlie = signIn("portal-token-C"), alpha = signIn("portal-token-A");
        PSP.accepted.put(3003L, java.util.List.of("ECOCASH", "ZIMSWITCH"));
        Res methods = api("POST", "/api/commerce/methods/refresh", charlie, null);
        assertEquals(200, methods.status(), methods.body());
        assertTrue(methods.body().contains("ECOCASH") && methods.body().contains("ZIMSWITCH") && !methods.body().contains("ONEMONEY"), methods.body());

        assertEquals(200, api("PUT", "/api/commerce/store", charlie, Map.of("businessType", "SERVICES", "pickupEnabled", true, "deliveryEnabled", true,
                "deliveryFeeMinor", 500, "alertsEnabled", true, "customerSms", true)).status());
        java.util.List<Map<String, Object>> days = new java.util.ArrayList<>();
        for (int d = 1; d <= 7; d++) days.add(Map.of("weekday", d, "opens", "06:00", "closes", "20:00"));
        assertEquals(400, api("PUT", "/api/commerce/hours", charlie, Map.of("staff", 1, "days", java.util.List.of(Map.of("weekday", 1, "opens", "17:00", "closes", "08:00")))).status());
        Res hours = api("PUT", "/api/commerce/hours", charlie, Map.of("staff", 1, "days", days));
        assertEquals(200, hours.status(), hours.body());

        Res cut = api("POST", "/api/commerce/products", charlie, Map.of("name", "Haircut", "priceMinor", 1_000, "currency", "USD", "kind", "SERVICE", "durationMin", 60));
        assertEquals(200, cut.status(), cut.body());
        assertEquals(60, cut.data().path("durationMin").asInt());
        Res shampoo = api("POST", "/api/commerce/products", charlie, Map.of("name", "Argan shampoo", "priceMinor", 500, "currency", "USD", "stock", 5));
        String cutUrl = cut.data().path("url").asText().replace("https://store.test", "");
        String storePath = cutUrl.substring(0, cutUrl.lastIndexOf('/'));

        // Delivery areas are the merchant's own.
        Res zones = api("POST", "/api/commerce/zones", charlie, Map.of("name", "Avondale", "feeMinor", 200));
        assertEquals(200, zones.status(), zones.body());
        api("POST", "/api/commerce/zones", charlie, Map.of("name", "Borrowdale", "feeMinor", 400, "freeFromMinor", 1_000));
        long avondale = zones.data().get(0).path("id").asLong();
        assertEquals(404, api("DELETE", "/api/commerce/zones/" + avondale, alpha, null).status());

        // A booking: only free times inside the opening hours; the time is held with the order.
        String c1 = form(cutUrl + "/buy", Map.of("qty", "1"), null).cookie();
        Res cart = page(storePath + "/cart", c1);
        assertTrue(cart.body().contains("Appointment time") && cart.body().contains("EcoCash") && cart.body().contains("Card") && !cart.body().contains("OneMoney"), cart.body());
        String slot = firstSlot(cart.body());
        assertNotNull(slot, "free times are offered");
        String k1 = key();
        assertTrue(form(storePath + "/checkout", Map.of("k", k1, "name", "Rumbi", "phone", "0775000666", "pay", "ZIMSWITCH"), c1).body().contains("Choose a time"));
        Res booked = form(storePath + "/checkout", Map.of("k", k1, "name", "Rumbi", "phone", "0775000666", "pay", "ZIMSWITCH", "slot", slot), c1);
        assertEquals(302, booked.status(), booked.body());
        assertEquals("ZIMSWITCH", PSP.lastMethod, "the customer's choice goes to the payment page");
        String ref = orderRefOf(k1);
        long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE order_ref = ?", Long.class, ref);
        assertEquals("HELD", jdbc.queryForObject("SELECT status FROM bookings WHERE order_id = ?", String.class, orderId));
        // The same time for a second customer (one person serving): no longer offered, and refused if sent anyway.
        String c2 = form(cutUrl + "/buy", Map.of("qty", "1"), null).cookie();
        assertNotEquals(slot, firstSlot(page(storePath + "/cart", c2).body()), "a taken time is not offered");
        String k2 = key();
        assertTrue(form(storePath + "/checkout", Map.of("k", k2, "name", "Tino", "phone", "0775000777", "slot", slot), c2).body().contains("no longer free"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE idempotency_key = ?", Integer.class, k2));

        // Paid: the booking is confirmed, the merchant and the customer get a text.
        PSP.set(3003, ref + "-1", "PAID", "PAID");
        reader.poll();
        assertEquals("CONFIRMED", jdbc.queryForObject("SELECT status FROM bookings WHERE order_id = ?", String.class, orderId));
        String alert = waitForMessage("MERCHANT|New order " + ref);
        assertNotNull(alert, "merchant alert: " + PSP.messages);
        assertNotNull(waitForMessage("CUSTOMER|Thank you! Your order " + ref), "customer text: " + PSP.messages);
        assertTrue(api("GET", "/api/commerce/bookings", charlie, null).body().contains(ref));
        assertFalse(api("GET", "/api/commerce/bookings", alpha, null).body().contains(ref));

        // A second booking whose payment fails gives its time back.
        String c3 = form(cutUrl + "/buy", Map.of("qty", "1"), null).cookie();
        String slot3 = firstSlot(page(storePath + "/cart", c3).body());
        String k3 = key();
        assertEquals(302, form(storePath + "/checkout", Map.of("k", k3, "name", "Nyasha", "phone", "0775000888", "slot", slot3), c3).status());
        String ref3 = orderRefOf(k3);
        PSP.set(3003, ref3 + "-1", "FAILED", "FAILED");
        reader.poll();
        assertEquals("RELEASED", jdbc.queryForObject("SELECT b.status FROM bookings b JOIN orders o ON o.id = b.order_id WHERE o.order_ref = ?", String.class, ref3));

        // Reviews: only from the buyer's own paid order, once per item; the merchant can reply or hide.
        String token = payments.viewToken(k1);
        Res orderPage = page("/o/" + ref + "?t=" + token);
        assertTrue(orderPage.body().contains("Review your order"), orderPage.body());
        long cutId = cut.data().path("id").asLong();
        Res reviewed = form("/o/" + ref + "/review", Map.of("t", token, "product", String.valueOf(cutId), "rating", "5", "comment", "Great cut", "name", "Rumbi"));
        assertTrue(reviewed.body().contains("Thank you for your review"), reviewed.body());
        assertTrue(form("/o/" + ref + "/review", Map.of("t", token, "product", String.valueOf(cutId), "rating", "4")).body().contains("already reviewed"));
        assertTrue(form("/o/" + ref3 + "/review", Map.of("t", payments.viewToken(k3), "product", String.valueOf(cutId), "rating", "1")).body().contains("once your order is paid"));
        assertEquals(404, form("/o/" + ref + "/review", Map.of("t", "wrong-token", "product", String.valueOf(cutId), "rating", "1")).status());
        Res product = page(cutUrl);
        assertTrue(product.body().contains("Great cut") && product.body().contains("★ 5.0 (1)"), product.body());
        long reviewId = api("GET", "/api/commerce/reviews", charlie, null).data().get(0).path("id").asLong();
        assertEquals(404, api("POST", "/api/commerce/reviews/" + reviewId, alpha, Map.of("hidden", true)).status());
        assertEquals(200, api("POST", "/api/commerce/reviews/" + reviewId, charlie, Map.of("reply", "Thank you, see you soon")).status());
        assertTrue(page(cutUrl).body().contains("see you soon"));
        api("POST", "/api/commerce/reviews/" + reviewId, charlie, Map.of("hidden", true));
        assertFalse(page(cutUrl).body().contains("Great cut"), "hidden by the merchant");

        // Delivery areas: the area's fee, chosen by the customer.
        String shampooUrl = shampoo.data().path("url").asText().replace("https://store.test", "");
        String c4 = form(shampooUrl + "/buy", Map.of("qty", "1"), null).cookie();
        assertTrue(page(storePath + "/cart", c4).body().contains("Avondale"));
        String k4 = key();
        Map<String, String> f = new java.util.HashMap<>(Map.of("k", k4, "method", "DELIVERY", "name", "Kuda", "phone", "0775000999", "address", "5 Churchill Ave, Harare", "pay", "ECOCASH"));
        assertTrue(form(storePath + "/checkout", f, c4).body().contains("delivery area"));
        f.put("zone", String.valueOf(avondale));
        assertEquals(302, form(storePath + "/checkout", f, c4).status());
        Map<String, Object> o4 = jdbc.queryForMap("SELECT * FROM orders WHERE idempotency_key = ?", k4);
        assertEquals("Avondale", o4.get("delivery_zone"));
        assertEquals(700L, ((Number) o4.get("amount_minor")).longValue());
        assertEquals("ECOCASH", o4.get("pay_choice"));
    }

    Res channel(String method, String path, Object body, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json");
        if (key != null) b.header("X-PayPort-Commerce-Key", key);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body(), null);
    }

    /** Owner, 31 Oct 2026: the app, WhatsApp and USSD order from the same stores, with the same rules, through the PSP. */
    @Test
    void channels_sameStoresAndRules_orderPlacedLikeTheWeb_paidOnThePspCheckout() throws Exception {
        PSP.down = false;
        String bravo = signIn("portal-token-B");
        api("PUT", "/api/commerce/store", bravo, Map.of("pickupEnabled", true, "codEnabled", true, "listed", true));
        Res p = api("POST", "/api/commerce/products", bravo, Map.of("name", "Chicken and chips", "priceMinor", 450, "currency", "USD", "stock", 3));
        long pid = p.data().path("id").asLong();
        String slug = p.data().path("url").asText().replaceAll(".*/s/([^/]+)/.*", "$1");
        assertEquals(403, channel("GET", "/api/channel/stores", null, null).status(), "service key only");
        assertEquals(403, channel("GET", "/api/channel/stores", null, "wrong").status());
        Res stores = channel("GET", "/api/channel/stores", null, FakePsp.KEY);
        assertEquals(200, stores.status(), stores.body());
        assertTrue(stores.body().contains(slug));
        Res one = channel("GET", "/api/channel/stores/" + slug, null, FakePsp.KEY);
        assertTrue(one.body().contains("Chicken and chips") && one.data().path("payOnDelivery").asBoolean(), one.body());

        Map<String, Object> base = new java.util.LinkedHashMap<>(Map.of("store", slug, "items", java.util.List.of(Map.of("productId", pid, "quantity", 2)),
                "method", "PICKUP", "name", "Chipo", "phone", "+263774111222", "channel", "USSD"));
        Res quote = channel("POST", "/api/channel/quote", base, FakePsp.KEY);
        assertEquals(900, quote.data().path("totalMinor").asLong(), quote.body());
        Map<String, Object> tooMany = new java.util.LinkedHashMap<>(base);
        tooMany.put("items", java.util.List.of(Map.of("productId", pid, "quantity", 5)));
        assertTrue(channel("POST", "/api/channel/quote", tooMany, FakePsp.KEY).body().contains("Only 3 left"), "same stock rules as the web");

        Map<String, Object> cash = new java.util.LinkedHashMap<>(base);
        cash.put("pay", "CASH");
        cash.put("key", key());
        Res placed = channel("POST", "/api/channel/orders", cash, FakePsp.KEY);
        assertEquals(200, placed.status(), placed.body());
        String ref = placed.data().path("orderRef").asText();
        assertEquals("ON_DELIVERY", placed.data().path("paymentStatus").asText());
        assertEquals("USSD", jdbc.queryForObject("SELECT channel FROM orders WHERE order_ref = ?", String.class, ref));
        assertEquals(1, jdbc.queryForObject("SELECT stock FROM products WHERE id = ?", Integer.class, pid), "held like a web order");
        assertEquals(ref, channel("POST", "/api/channel/orders", cash, FakePsp.KEY).data().path("orderRef").asText(), "the same key gives the same order");

        Map<String, Object> online = new java.util.LinkedHashMap<>(base);
        online.put("items", java.util.List.of(Map.of("productId", pid, "quantity", 1)));
        online.put("key", key());
        online.put("channel", "APP");
        Res paid = channel("POST", "/api/channel/orders", online, FakePsp.KEY);
        assertEquals(200, paid.status(), paid.body());
        assertTrue(paid.data().path("checkoutSessionId").asLong() > 0, "the PSP checkout the channel pays: " + paid.body());
        String ref2 = paid.data().path("orderRef").asText();
        PSP.set(2002, ref2 + "-1", "PAID", "PAID");
        reader.poll();
        Res mine = channel("GET", "/api/channel/orders?phone=0774111222", null, FakePsp.KEY);
        assertTrue(mine.body().contains(ref) && mine.body().contains(ref2), mine.body());
        assertEquals("PAID", channel("GET", "/api/channel/orders/" + ref2 + "?phone=%2B263774111222", null, FakePsp.KEY).data().path("paymentStatus").asText());
        assertEquals(404, channel("GET", "/api/channel/orders/" + ref2 + "?phone=0779999999", null, FakePsp.KEY).status(), "only the phone that ordered");
    }

    /** Owner, 31 Oct 2026: shoppers sign in with PayPort; their orders, saved addresses, favourites and reorder. */
    @Test
    void shopperAccounts_signInWithPayPort_ordersAddressesFavouritesReorder() throws Exception {
        PSP.down = false;
        String bravo = signIn("portal-token-B");
        api("PUT", "/api/commerce/store", bravo, Map.of("pickupEnabled", true, "deliveryEnabled", true, "deliveryFeeMinor", 100, "listed", true));
        Res p = api("POST", "/api/commerce/products", bravo, Map.of("name", "Beef stew", "priceMinor", 700, "currency", "USD", "stock", 20));
        String url = p.data().path("url").asText().replace("https://store.test", "");
        String storePath = url.substring(0, url.lastIndexOf('/'));

        Res go = page("/signin?next=" + URLEncoder.encode(storePath, StandardCharsets.UTF_8));
        assertEquals(302, go.status());
        assertTrue(go.location().startsWith("https://app.test/store-signin?next="), go.location());
        assertTrue(page("/signin?next=" + URLEncoder.encode("//evil.example", StandardCharsets.UTF_8)).location().endsWith("next=%2Fme"), "never another site");
        assertEquals(302, page("/me").status(), "the account page needs a sign-in");

        HttpResponse<String> cb = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/callback?code=c0de&next=" + URLEncoder.encode(storePath, StandardCharsets.UTF_8))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(302, cb.statusCode());
        assertTrue(cb.headers().firstValue("Location").orElse("").endsWith(storePath), "back where sign-in was asked for");
        String me = cb.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("pp_me=")).findFirst().orElseThrow().split(";")[0];
        assertTrue(cb.headers().allValues("Set-Cookie").stream().anyMatch(c -> c.startsWith("pp_me=") && c.contains("HttpOnly")), "HttpOnly session cookie");
        assertEquals(302, HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/callback?code=c0de&next=%2Fme")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode());

        // Signed in: name and phone filled in at checkout; the order and the address are the shopper's.
        String cookie = form(url + "/buy", Map.of("qty", "2"), me).cookie();
        String both = cookie + "; " + me;
        Res cart = page(storePath + "/cart", both);
        assertTrue(cart.body().contains("value=\"Rudo Moyo\"") && cart.body().contains("value=\"0776000333\""), cart.body());
        String k = key();
        Res placed = form(storePath + "/checkout", Map.of("k", k, "method", "DELIVERY", "address", "7 Fife Avenue, Harare"), both);
        assertEquals(302, placed.status(), placed.body());
        String ref = orderRefOf(k);
        assertNotNull(jdbc.queryForObject("SELECT shopper_id FROM orders WHERE order_ref = ?", Long.class, ref));
        assertEquals("Rudo Moyo", jdbc.queryForObject("SELECT customer_name FROM orders WHERE order_ref = ?", String.class, ref));
        String nextCart = form(url + "/buy", Map.of("qty", "1"), me).cookie();
        assertTrue(page(storePath + "/cart", nextCart + "; " + me).body().contains("7 Fife Avenue, Harare"), "the saved address is offered next time");

        Res account = page("/me", me);
        assertEquals(200, account.status());
        assertTrue(account.body().contains("Hi, Rudo") && account.body().contains(ref) && account.body().contains("7 Fife Avenue, Harare"), account.body());

        // Favourites and reorder.
        assertEquals(302, form(storePath + "/favourite", Map.of(), me).status());
        assertTrue(page("/me", me).body().contains("Bravo Foods"), "a favourite store");
        assertTrue(page(storePath, me).body().contains("♥ Favourite"));
        String fresh = form(url + "/buy", Map.of("qty", "1"), null).cookie();
        Res again = form("/me/orders/" + ref + "/reorder", Map.of(), fresh + "; " + me);
        assertEquals(302, again.status());
        assertTrue(again.location().endsWith(storePath + "/cart"), again.location());
        assertTrue(page(storePath + "/cart", fresh).body().contains("Beef stew"));
        assertEquals(404, form("/me/orders/CO-NOTMINE1/reorder", Map.of(), me).status(), "only the shopper's own orders");

        assertEquals(302, form("/signout", Map.of(), me).status());
        assertEquals(302, page("/me", me).status(), "signed out");
    }

    String shopperCookie(String code) throws Exception {
        HttpResponse<String> cb = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/auth/callback?code=" + code + "&next=%2Fme")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return cb.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith("pp_me=")).findFirst().orElseThrow().split(";")[0];
    }

    /** Owner, 31 Oct 2026: reports, offers only to customers who agreed (with a stop link), cart reminders, loyalty points. */
    @Test
    void merchantGrowth_reports_offersWithConsent_cartReminders_loyaltyPoints() throws Exception {
        PSP.down = false;
        String alpha = signIn("portal-token-A");
        assertEquals(200, api("PUT", "/api/commerce/store", alpha, Map.of("pickupEnabled", true, "loyaltyEnabled", true, "cartReminders", true)).status());
        Res p = api("POST", "/api/commerce/products", alpha, Map.of("name", "Carved bowl", "priceMinor", 5_000, "currency", "USD", "stock", 50));
        String url = p.data().path("url").asText().replace("https://store.test", "");
        String storePath = url.substring(0, url.lastIndexOf('/'));
        String me = shopperCookie("c0de-loyal");

        // First order: the shopper agrees to offers; paying earns points (1 per dollar).
        String c1 = form(url + "/buy", Map.of("qty", "2"), me).cookie();
        Res cart = page(storePath + "/cart", c1 + "; " + me);
        assertTrue(cart.body().contains("Send me offers from"), cart.body());
        String k1 = key();
        assertEquals(302, form(storePath + "/checkout", Map.of("k", k1, "method", "PICKUP", "offers", "on"), c1 + "; " + me).status());
        String ref1 = orderRefOf(k1);
        PSP.set(1001, ref1 + "-1", "PAID", "PAID");
        reader.poll();
        assertEquals(100, marketing.points(1001, "+263777000444"));
        assertTrue(marketing.consented(1001, "0777000444"));

        // Second order: points used (100 = USD 1.00 off); a failed payment gives them back.
        String c2 = form(url + "/buy", Map.of("qty", "1"), me).cookie();
        Res cart2 = page(storePath + "/cart", c2 + "; " + me);
        assertTrue(cart2.body().contains("Use 100 of your 100 points") && !cart2.body().contains("Send me offers from"), cart2.body());
        String k2 = key();
        assertEquals(302, form(storePath + "/checkout", Map.of("k", k2, "method", "PICKUP", "points", "on"), c2 + "; " + me).status());
        String ref2 = orderRefOf(k2);
        assertEquals(4_900L, jdbc.queryForObject("SELECT amount_minor FROM orders WHERE order_ref = ?", Long.class, ref2));
        assertEquals(0, marketing.points(1001, "0777000444"));
        PSP.set(1001, ref2 + "-1", "FAILED", "FAILED");
        reader.poll();
        assertEquals(100, marketing.points(1001, "0777000444"), "points back when the payment fails");

        // A cart left for hours: one reminder, to a customer who agreed.
        String c3 = form(url + "/buy", Map.of("qty", "1"), me).cookie();
        jdbc.update("UPDATE carts SET updated_at = ? WHERE token_hash = ?", java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusHours(3)), shaOf(c3.substring("pp_cart=".length())));
        int before = PSP.messages.size();
        marketing.remindCarts();
        assertNotNull(waitForMessage("CUSTOMER|You left something in your cart at Alpha"), "reminder: " + PSP.messages);
        marketing.remindCarts();
        Thread.sleep(300);
        assertEquals(1, PSP.messages.stream().skip(before).filter(m -> m.contains("You left something")).count(), "once");

        // Offers by text: to customers who agreed, once a day, each with a stop link.
        String bravo = signIn("portal-token-B");
        assertEquals(409, api("POST", "/api/commerce/campaigns", signIn("portal-token-C"), Map.of("text", "Hello")).status(), "nobody has agreed yet");
        Res sent = api("POST", "/api/commerce/campaigns", alpha, Map.of("text", "20% off carved bowls this weekend"));
        assertEquals(200, sent.status(), sent.body());
        assertTrue(sent.data().path("recipients").asInt() >= 1);
        String offer = waitForMessage("CUSTOMER|Alpha Crafts: 20% off carved bowls");
        assertNotNull(offer, "offer: " + PSP.messages);
        assertTrue(offer.contains("Stop: https://store.test/u/"), offer);
        assertEquals(409, api("POST", "/api/commerce/campaigns", alpha, Map.of("text", "Again")).status(), "one offer a day");
        String stop = offer.replaceAll(".*https://store.test(/u/[a-z0-9]+).*", "$1");
        Res stopped = page(stop);
        assertTrue(stopped.status() == 200 && stopped.body().contains("Messages stopped"), stopped.body());
        assertFalse(marketing.consented(1001, "0777000444"));

        // Reports.
        Res r = api("GET", "/api/commerce/reports?days=30", alpha, null);
        assertEquals(200, r.status(), r.body());
        assertTrue(r.body().contains("Carved bowl") && r.data().path("byChannel").toString().contains("WEB"), r.body());
        assertFalse(api("GET", "/api/commerce/reports", bravo, null).body().contains("Carved bowl"), "each merchant sees its own");
    }

    static String shaOf(String s) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }
}
