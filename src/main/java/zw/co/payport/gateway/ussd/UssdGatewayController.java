package zw.co.payport.gateway.ussd;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.w3c.dom.Document;
import zw.co.payport.gateway.model.response.ApiResponse;
import zw.co.payport.gateway.platform.Web;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Direct USSD gateways of the Zimbabwean networks, each in its own protocol, all on the same menus
 * (UssdService) and the same payment path as the consumer site and the app:
 *
 *   POST /api/v1/ussd/econet   Econet messaging XML (MessageRequest / messageResponse, stage FIRST|PENDING|COMPLETE)
 *   POST /api/v1/ussd/netone   NetOne dynamic menu XML (USSDDynMenuRequest / USSDDynMenuResponse, rspFlag 1 continue, 2 end)
 *   POST /api/v1/ussd/telecel  Telecel JSON (short_message "80|74|78 &lt;session&gt; ... &lt;input&gt;", reply 72 continue, 81 end)
 *
 * Each call presents the network's access key (?key= or X-PayPort-Ussd-Key) or comes from one of its
 * allowed IP addresses; the network must be switched on in ops.
 */
@Slf4j
@RestController
public class UssdGatewayController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final UssdService ussd;
    private final UssdNetworkService networks;

    public UssdGatewayController(UssdService ussd, UssdNetworkService networks) {
        this.ussd = ussd;
        this.networks = networks;
    }

    // ─── Econet ──────────────────────────────────────────────────────────────

    @PostMapping(value = "/api/v1/ussd/econet", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> econet(@RequestBody String body, @RequestParam(required = false) String key,
                                         @RequestHeader(value = "X-PayPort-Ussd-Key", required = false) String header, HttpServletRequest req) {
        Document d;
        try {
            d = UssdNetworkService.xml(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_XML).body("<error>Unreadable request</error>");
        }
        String txId = UssdNetworkService.text(d, "transactionID");
        String subscriber = UssdNetworkService.text(d, "sourceNumber");
        String shortCode = UssdNetworkService.text(d, "destinationNumber");
        String message = UssdNetworkService.text(d, "message");
        String stage = UssdNetworkService.text(d, "stage");
        String refused = networks.refuse("ECONET", key != null ? key : header, req.getRemoteAddr());
        UssdService.Hop hop;
        if (refused != null) {
            log.warn("[USSD-ECONET] refused from {}: {}", req.getRemoteAddr(), refused);
            hop = new UssdService.Hop("PayPort is not available at the moment.", true);
        } else if (txId == null || subscriber == null) {
            hop = new UssdService.Hop("Invalid request.", true);
        } else if ("COMPLETE".equalsIgnoreCase(stage)) {
            hop = new UssdService.Hop("Goodbye.", true);
        } else {
            hop = ussd.converse("ECONET", txId, subscriber, message, "FIRST".equalsIgnoreCase(stage));
        }
        String xml = "<messageResponse xmlns=\"http://econet.co.zw/intergration/messagingSchema\">"
                + "<transactionTime>" + OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "</transactionTime>"
                + "<transactionID>" + UssdNetworkService.esc(txId) + "</transactionID>"
                + "<sourceNumber>" + UssdNetworkService.esc(shortCode) + "</sourceNumber>"
                + "<destinationNumber>" + UssdNetworkService.esc(subscriber) + "</destinationNumber>"
                + "<message>" + UssdNetworkService.esc(hop.text()) + "</message>"
                + "<stage>" + (hop.end() ? "COMPLETE" : "PENDING") + "</stage>"
                + "<channel>USSD</channel>"
                + "<applicationTransactionID>" + UssdNetworkService.esc(appId("ECONET", txId)) + "</applicationTransactionID>"
                + "<transactionType>" + (hop.end() ? "" : "MENU_PROCESSING") + "</transactionType>"
                + "</messageResponse>";
        return ResponseEntity.status(refused != null ? 401 : 200).contentType(MediaType.APPLICATION_XML).body(xml);
    }

    // ─── NetOne ──────────────────────────────────────────────────────────────

    @PostMapping(value = "/api/v1/ussd/netone", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> netone(@RequestBody String body, @RequestParam(required = false) String key,
                                         @RequestHeader(value = "X-PayPort-Ussd-Key", required = false) String header, HttpServletRequest req) {
        Document d;
        try {
            d = UssdNetworkService.xml(body);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_XML).body("<error>Unreadable request</error>");
        }
        String requestId = UssdNetworkService.text(d, "requestId");
        String msisdn = UssdNetworkService.text(d, "msisdn");
        String starCode = UssdNetworkService.text(d, "starCode");
        String userData = UssdNetworkService.text(d, "userData");
        String timeStamp = UssdNetworkService.text(d, "timeStamp");
        String refused = networks.refuse("NETONE", key != null ? key : header, req.getRemoteAddr());
        UssdService.Hop hop;
        if (refused != null) {
            log.warn("[USSD-NETONE] refused from {}: {}", req.getRemoteAddr(), refused);
            hop = new UssdService.Hop("PayPort is not available at the moment.", true);
        } else if (requestId == null || msisdn == null) {
            hop = new UssdService.Hop("Invalid request.", true);
        } else {
            boolean first = userData == null || userData.isBlank() || userData.equals(starCode) || (userData.startsWith("*") && userData.endsWith("#"));
            hop = ussd.converse("NETONE", requestId, msisdn, first ? "" : userData, first);
        }
        String url = req.getRequestURL() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
        String xml = "<USSDDynMenuResponse>"
                + "<requestId>" + UssdNetworkService.esc(requestId) + "</requestId>"
                + "<msisdn>" + UssdNetworkService.esc(msisdn) + "</msisdn>"
                + "<starCode>" + UssdNetworkService.esc(starCode) + "</starCode>"
                + "<dataSet><param>"
                + "<id>1</id>"
                + "<value>" + UssdNetworkService.esc(hop.text()) + "</value>"
                + "<index>1</index>"
                + "<rspFlag>" + (hop.end() ? "2" : "1") + "</rspFlag>"
                + "<rspURL>" + UssdNetworkService.esc(url) + "</rspURL>"
                + "<rspTag>0</rspTag>"
                + "<default>1</default>"
                + "<appendIndex>0</appendIndex>"
                + "<transactionType>" + (hop.end() ? "" : "MENU_PROCESSING") + "</transactionType>"
                + "</param></dataSet>"
                + "<ErrCode>1</ErrCode>"
                + "<errURL>" + UssdNetworkService.esc(url) + "</errURL>"
                + "<transferCode>" + UssdNetworkService.esc(requestId) + "</transferCode>"
                + "<timeStamp>" + UssdNetworkService.esc(timeStamp) + "</timeStamp>"
                + "</USSDDynMenuResponse>";
        return ResponseEntity.status(refused != null ? 401 : 200).contentType(MediaType.APPLICATION_XML).body(xml);
    }

    // ─── Telecel ─────────────────────────────────────────────────────────────

    @PostMapping(value = "/api/v1/ussd/telecel", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> telecel(@RequestBody String body, @RequestParam(required = false) String key,
                                                       @RequestHeader(value = "X-PayPort-Ussd-Key", required = false) String header, HttpServletRequest req) {
        JsonNode n;
        try {
            n = JSON.readTree(body);
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unreadable request"));
        }
        JsonNode sm = n.path("short_message");
        String raw = (sm.isTextual() ? sm.asText() : sm.path("message").asText("")).trim();
        String[] parts = raw.split("\\s+");
        String type = parts.length > 0 ? parts[0] : "";
        String session = parts.length > 1 ? parts[1] : "";
        String input = parts.length > 2 ? parts[parts.length - 1] : "";
        String subscriber = n.path("source_addr").asText("");
        String shortCode = n.path("destination_addr").asText("");
        String refused = networks.refuse("TELECEL", key != null ? key : header, req.getRemoteAddr());
        UssdService.Hop hop;
        if (refused != null) {
            log.warn("[USSD-TELECEL] refused from {}: {}", req.getRemoteAddr(), refused);
            hop = new UssdService.Hop("PayPort is not available at the moment.", true);
        } else if (session.isEmpty() || subscriber.isEmpty()) {
            hop = new UssdService.Hop("Invalid request.", true);
        } else if ("78".equals(type)) {
            hop = new UssdService.Hop("Goodbye.", true);
        } else {
            boolean first = "80".equals(type);
            hop = ussd.converse("TELECEL", session, subscriber, first ? "" : input, first);
        }
        if (shortCode.isBlank()) {
            Object sc = networks.get("TELECEL").get("shortCode");
            shortCode = sc == null ? "" : sc.toString().replaceAll("[^0-9]", "");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source_addr_ton", 5);
        out.put("source_addr_npi", 1);
        out.put("source_addr", shortCode);
        out.put("destination_addr", subscriber);
        out.put("dest_port", 9280);
        out.put("dest_addr_ton", 1);
        out.put("dest_addr_npi", 1);
        out.put("short_message", (hop.end() ? "81" : "72") + " " + session + " 0 " + hop.text());
        return ResponseEntity.status(refused != null ? 401 : 200).body(out);
    }

    private static String appId(String network, String txId) {
        return UssdNetworkService.sha256(network + "|" + txId).substring(0, 12).toUpperCase();
    }

    // ─── ops ─────────────────────────────────────────────────────────────────

    @GetMapping("/api/v1/admin/ussd/networks")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> list(HttpServletRequest req) {
        return Web.run(networks::list, req);
    }

    public record NetworkBody(Boolean enabled, String shortCode, String allowedIps) {}

    @PutMapping("/api/v1/admin/ussd/networks/{code}")
    @PreAuthorize("hasAnyRole('ADMIN','OPS')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> update(@PathVariable String code, @RequestBody NetworkBody b, HttpServletRequest req) {
        return Web.run(() -> networks.update(code, b.enabled(), b.shortCode(), b.allowedIps(), "staff:" + Web.principal()), req);
    }

    @PostMapping("/api/v1/admin/ussd/networks/{code}/access-key")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> rotateKey(@PathVariable String code, HttpServletRequest req) {
        return Web.run(() -> networks.rotateKey(code, "staff:" + Web.principal()), req);
    }
}
