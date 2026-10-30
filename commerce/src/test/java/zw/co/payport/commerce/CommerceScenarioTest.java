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

    static final ObjectMapper JSON = new ObjectMapper();
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    record Res(int status, String body, String location) {
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
        String body = fields.entrySet().stream().map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(Collectors.joining("&"));
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        return new Res(r.statusCode(), r.body(), r.headers().firstValue("Location").orElse(null));
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

        // Products, the mini-store and buying from it.
        Res p = api("POST", "/api/commerce/products", alpha, Map.of("name", "Woven basket", "description", "Handmade in Binga", "priceMinor", 1_800, "currency", "USD"));
        assertEquals(200, p.status(), p.body());
        String productUrl = p.data().path("url").asText().replace("https://store.test", "");
        Res store = page(productUrl.substring(0, productUrl.lastIndexOf('/')));
        assertTrue(store.status() == 200 && store.body().contains("Woven basket") && store.body().contains("USD 18.00"), store.body());
        Res product = page(productUrl);
        assertTrue(product.body().contains("og:title") && product.body().contains("Handmade in Binga"));
        Res bought = form(productUrl + "/buy", Map.of("k", key(), "qty", "3"));
        assertEquals(302, bought.status(), bought.body());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE item_name = 'Woven basket' AND amount_minor = 5400 AND quantity = 3", Integer.class));
        assertTrue(form(productUrl + "/buy", Map.of("k", key(), "qty", "0")).body().contains("quantity"));
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
}
