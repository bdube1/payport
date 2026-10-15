package zw.co.payport.gateway.psp.risk;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import zw.co.payport.gateway.psp.integration.IntegrationSpis;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * Live sanctions list feeds with public formats:
 *  - UN Security Council Consolidated List (XML)
 *  - US OFAC SDN list (CSV: sdn.csv + alt.csv for aliases)
 * The domestic / FIU list is uploaded by compliance as CSV (see AmlScreeningService.importLocalList).
 * URLs are configuration so they can be changed if the publishers move them.
 */
public final class SanctionsListSources {
    private SanctionsListSources() {}

    static byte[] get(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<byte[]> r = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(2)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode() + " from " + url);
        return r.body();
    }

    @Component
    public static class UnConsolidated implements IntegrationSpis.SanctionsListSource {
        @Value("${payport.psp.aml.un-list-url:https://scsanctions.un.org/resources/xml/en/consolidated.xml}")
        private String url = "https://scsanctions.un.org/resources/xml/en/consolidated.xml";
        @Value("${payport.psp.aml.feeds-enabled:false}")
        private boolean enabled;

        @Override public String listCode() { return "UN"; }

        @Override
        public List<IntegrationSpis.SanctionsEntry> fetch() throws Exception {
            if (!enabled) return null;
            return parse(get(url));
        }

        /** Parses INDIVIDUAL and ENTITY records. External entities and DTDs are disabled (XXE-safe). */
        public static List<IntegrationSpis.SanctionsEntry> parse(byte[] xml) throws Exception {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setExpandEntityReferences(false);
            Document d = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
            List<IntegrationSpis.SanctionsEntry> out = new ArrayList<>();
            for (String tag : List.of("INDIVIDUAL", "ENTITY")) {
                NodeList nl = d.getElementsByTagName(tag);
                for (int i = 0; i < nl.getLength(); i++) {
                    Element e = (Element) nl.item(i);
                    String id = text(e, "DATAID");
                    String name = String.join(" ", nonBlank(text(e, "FIRST_NAME"), text(e, "SECOND_NAME"), text(e, "THIRD_NAME"), text(e, "FOURTH_NAME")));
                    List<String> aliases = new ArrayList<>();
                    NodeList al = e.getElementsByTagName(tag.equals("INDIVIDUAL") ? "INDIVIDUAL_ALIAS" : "ENTITY_ALIAS");
                    for (int j = 0; j < al.getLength(); j++) {
                        String a = text((Element) al.item(j), "ALIAS_NAME");
                        if (a != null && !a.isBlank()) aliases.add(a);
                    }
                    String nat = null;
                    NodeList n = e.getElementsByTagName("NATIONALITY");
                    if (n.getLength() > 0) nat = text((Element) n.item(0), "VALUE");
                    String dob = null;
                    NodeList db = e.getElementsByTagName("INDIVIDUAL_DATE_OF_BIRTH");
                    if (db.getLength() > 0) {
                        Element de = (Element) db.item(0);
                        dob = Optional.ofNullable(text(de, "DATE")).orElse(text(de, "YEAR"));
                    }
                    if (id != null && !name.isBlank()) {
                        out.add(new IntegrationSpis.SanctionsEntry("UN", id, tag.equals("INDIVIDUAL") ? "INDIVIDUAL" : "ENTITY",
                                name, aliases, nat, dob, text(e, "UN_LIST_TYPE")));
                    }
                }
            }
            return out;
        }
    }

    @Component
    public static class OfacSdn implements IntegrationSpis.SanctionsListSource {
        @Value("${payport.psp.aml.ofac-sdn-url:https://www.treasury.gov/ofac/downloads/sdn.csv}")
        private String sdnUrl = "https://www.treasury.gov/ofac/downloads/sdn.csv";
        @Value("${payport.psp.aml.ofac-alt-url:https://www.treasury.gov/ofac/downloads/alt.csv}")
        private String altUrl = "https://www.treasury.gov/ofac/downloads/alt.csv";
        @Value("${payport.psp.aml.feeds-enabled:false}")
        private boolean enabled;

        @Override public String listCode() { return "OFAC"; }

        @Override
        public List<IntegrationSpis.SanctionsEntry> fetch() throws Exception {
            if (!enabled) return null;
            return parse(new String(get(sdnUrl), StandardCharsets.ISO_8859_1), new String(get(altUrl), StandardCharsets.ISO_8859_1));
        }

        /** sdn.csv: ent_num, SDN_Name, SDN_Type, Program, ...; alt.csv: ent_num, alt_num, alt_type, alt_name, remarks. */
        public static List<IntegrationSpis.SanctionsEntry> parse(String sdnCsv, String altCsv) throws Exception {
            Map<String, List<String>> aliases = new HashMap<>();
            if (altCsv != null) {
                for (List<String> r : Csv.parse(altCsv)) {
                    if (r.size() >= 4) aliases.computeIfAbsent(r.get(0).trim(), k -> new ArrayList<>()).add(r.get(3).trim());
                }
            }
            List<IntegrationSpis.SanctionsEntry> out = new ArrayList<>();
            for (List<String> r : Csv.parse(sdnCsv)) {
                if (r.size() < 4 || !r.get(0).trim().matches("\\d+")) continue;
                String type = r.get(2).trim().equalsIgnoreCase("individual") ? "INDIVIDUAL" : "ENTITY";
                if (r.get(2).trim().equalsIgnoreCase("vessel") || r.get(2).trim().equalsIgnoreCase("aircraft")) type = r.get(2).trim().toUpperCase();
                out.add(new IntegrationSpis.SanctionsEntry("OFAC", r.get(0).trim(), type, r.get(1).trim(),
                        aliases.getOrDefault(r.get(0).trim(), List.of()), null, null, r.get(3).trim()));
            }
            return out;
        }
    }

    private static String text(Element e, String tag) {
        NodeList nl = e.getElementsByTagName(tag);
        if (nl.getLength() == 0) return null;
        Node n = nl.item(0);
        String t = n.getTextContent();
        return t == null ? null : t.trim();
    }

    private static List<String> nonBlank(String... parts) {
        List<String> l = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isBlank()) l.add(p.trim());
        return l;
    }

    /** Minimal RFC 4180 CSV reader (quoted fields, embedded commas and quotes). */
    public static final class Csv {
        private Csv() {}

        public static List<List<String>> parse(String text) throws Exception {
            List<List<String>> rows = new ArrayList<>();
            List<String> row = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            BufferedReader in = new BufferedReader(new StringReader(text));
            int c;
            while ((c = in.read()) != -1) {
                char ch = (char) c;
                if (quoted) {
                    if (ch == '"') {
                        in.mark(1);
                        int next = in.read();
                        if (next == '"') field.append('"');
                        else { quoted = false; if (next != -1) in.reset(); }
                    } else field.append(ch);
                } else if (ch == '"') quoted = true;
                else if (ch == ',') { row.add(field.toString()); field.setLength(0); }
                else if (ch == '\n') { row.add(field.toString()); field.setLength(0); rows.add(row); row = new ArrayList<>(); }
                else if (ch != '\r') field.append(ch);
            }
            if (field.length() > 0 || !row.isEmpty()) { row.add(field.toString()); rows.add(row); }
            return rows;
        }
    }
}
