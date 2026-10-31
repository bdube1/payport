package zw.co.payport.gateway.intelligence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import zw.co.payport.gateway.care.ComplaintCatalog;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Complaint triage: suggests the complaint type (and so its priority and deadline) from the customer's words. A
 * multinomial naive Bayes text model, trained here on Payport's own complaints (the type staff chose is the label)
 * and seeded from the complaint catalogue, so it helps from the first day and gets better as complaints are logged.
 * It only suggests: staff pick the type. Measured on complaints it was not trained on (accuracy, and how often the
 * right type is in its top three); each suggestion made when a complaint is opened is kept with what staff chose.
 */
@Service
public class ComplaintTriage {

    private static final Logger log = LoggerFactory.getLogger(ComplaintTriage.class);
    static final Set<String> STOP = Set.of("the", "and", "for", "but", "not", "was", "were", "has", "have", "had", "with", "this", "that", "from", "they",
            "them", "their", "there", "you", "your", "our", "his", "her", "she", "him", "are", "been", "into", "out", "per", "via", "after", "before",
            "when", "then", "than", "which", "who", "what", "did", "does", "can", "could", "would", "should", "will", "just", "also", "please", "customer",
            "says", "said", "reports", "complains", "complaint", "today", "yesterday", "about", "because", "still", "yet", "only");

    /** A trained model: word counts per complaint type. */
    record Model(int version, Map<String, Map<String, Integer>> words, Map<String, Integer> totals, Map<String, Integer> docs, int vocabulary, int samples) {}

    private final JdbcTemplate jdbc;
    private final Intelligence intelligence;
    private volatile Model model;

    public ComplaintTriage(JdbcTemplate jdbc, Intelligence intelligence) {
        this.jdbc = jdbc;
        this.intelligence = intelligence;
    }

    static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String w : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z]+", " ").trim().split(" ")) {
            if (w.length() < 3 || STOP.contains(w)) continue;
            if (w.length() > 5 && w.endsWith("ing")) w = w.substring(0, w.length() - 3);
            else if (w.length() > 4 && w.endsWith("ed")) w = w.substring(0, w.length() - 2);
            else if (w.length() > 3 && w.endsWith("s") && !w.endsWith("ss")) w = w.substring(0, w.length() - 1);
            out.add(w);
        }
        return out;
    }

    record Doc(String label, List<String> words) {}

    /** The catalogue's own words for each type (title twice, cause once): what the model knows before any complaint. */
    static List<Doc> seed() {
        List<Doc> d = new ArrayList<>();
        for (ComplaintCatalog.Category c : ComplaintCatalog.ALL) {
            List<String> w = new ArrayList<>(tokens(c.title()));
            w.addAll(tokens(c.title()));
            w.addAll(tokens(c.cause()));
            d.add(new Doc(c.code(), w));
        }
        return d;
    }

    static Model train(int version, List<Doc> docs) {
        Map<String, Map<String, Integer>> words = new HashMap<>();
        Map<String, Integer> totals = new HashMap<>(), counts = new HashMap<>();
        Set<String> vocab = new java.util.HashSet<>();
        for (Doc d : docs) {
            counts.merge(d.label(), 1, Integer::sum);
            Map<String, Integer> w = words.computeIfAbsent(d.label(), k -> new HashMap<>());
            for (String t : d.words()) {
                w.merge(t, 1, Integer::sum);
                totals.merge(d.label(), 1, Integer::sum);
                vocab.add(t);
            }
        }
        return new Model(version, words, totals, counts, Math.max(1, vocab.size()), docs.size());
    }

    /** Types in order of likelihood, with probabilities that add up to 1. */
    static List<Map<String, Object>> rank(Model m, String text, int top) {
        List<String> ws = tokens(text);
        int n = m.docs().values().stream().mapToInt(Integer::intValue).sum();
        Map<String, Double> logp = new HashMap<>();
        for (ComplaintCatalog.Category c : ComplaintCatalog.ALL) {
            String k = c.code();
            double lp = Math.log((m.docs().getOrDefault(k, 0) + 1.0) / (n + ComplaintCatalog.ALL.size()));
            Map<String, Integer> w = m.words().getOrDefault(k, Map.of());
            int total = m.totals().getOrDefault(k, 0);
            for (String t : ws) lp += Math.log((w.getOrDefault(t, 0) + 1.0) / (total + m.vocabulary()));
            logp.put(k, lp);
        }
        double max = logp.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double sum = logp.values().stream().mapToDouble(v -> Math.exp(v - max)).sum();
        List<Map<String, Object>> out = new ArrayList<>();
        logp.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder())).limit(top).forEach(e -> {
            ComplaintCatalog.Category c = ComplaintCatalog.find(e.getKey()).orElseThrow();
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("category", c.code());
            s.put("title", c.title());
            s.put("area", c.area());
            s.put("priority", c.priority());
            s.put("confidence", Math.round(Math.exp(e.getValue() - max) / sum * 100) / 100.0);
            out.add(s);
        });
        return out;
    }

    List<Doc> learned() {
        List<Doc> d = new ArrayList<>();
        for (Map<String, Object> r : jdbc.queryForList("SELECT category, description FROM complaints WHERE description IS NOT NULL ORDER BY id")) {
            if (ComplaintCatalog.find((String) r.get("category")).isPresent()) d.add(new Doc((String) r.get("category"), tokens((String) r.get("description"))));
        }
        return d;
    }

    /** Trains on the catalogue and every logged complaint; measured first on the newest fifth, which it has not seen. */
    @Scheduled(cron = "${payport.intelligence.triage-cron:0 10 2 * * *}")
    public synchronized Map<String, Object> retrain() {
        List<Doc> seed = seed(), learned = learned();
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("complaints", learned.size());
        if (learned.size() >= 20) {
            int cut = learned.size() * 4 / 5;
            List<Doc> train = new ArrayList<>(seed);
            train.addAll(learned.subList(0, cut));
            Model probe = train(0, train);
            int right = 0, top3 = 0;
            List<Doc> test = learned.subList(cut, learned.size());
            for (Doc d : test) {
                List<Map<String, Object>> r = rank(probe, String.join(" ", d.words()), 3);
                if (!r.isEmpty() && d.label().equals(r.get(0).get("category"))) right++;
                if (r.stream().anyMatch(x -> d.label().equals(x.get("category")))) top3++;
            }
            metrics.put("tested", test.size());
            metrics.put("accuracy", Math.round(right * 1000.0 / test.size()) / 1000.0);
            metrics.put("top3", Math.round(top3 * 1000.0 / test.size()) / 1000.0);
        } else {
            metrics.put("tested", 0);
        }
        List<Doc> all = new ArrayList<>(seed);
        all.addAll(learned);
        int version = intelligence.saveModel(Intelligence.TRIAGE, all.size(), metrics);
        model = train(version, all);
        log.info("[INTELLIGENCE] complaint triage v{} trained on {} complaints: {}", version, learned.size(), metrics);
        return intelligence.latestModel(Intelligence.TRIAGE);
    }

    Model model() {
        Model m = model;
        if (m == null) {
            retrain();
            m = model;
        }
        return m;
    }

    /** Suggestions for the complaint form (only while the feature is on). */
    public Map<String, Object> suggest(String text) {
        intelligence.require(Intelligence.TRIAGE);
        if (text == null || text.trim().length() < 8) return Map.of("suggestions", List.of());
        Model m = model();
        return Map.of("suggestions", rank(m, text, 3), "modelVersion", m.version());
    }

    /** When a complaint is opened: what the model would have said, kept next to what staff chose. */
    public void record(String complaintRef, String text, String chosen) {
        if (!intelligence.on(Intelligence.TRIAGE)) return;
        try {
            Model m = model();
            List<Map<String, Object>> r = rank(m, text, 3);
            if (r.isEmpty()) return;
            intelligence.predict(Intelligence.TRIAGE, complaintRef, m.version(), (String) r.get(0).get("category"), ((Number) r.get(0).get("confidence")).doubleValue(),
                    Map.of("top3", r.stream().map(x -> x.get("category")).toList()));
            intelligence.outcome(Intelligence.TRIAGE, complaintRef, chosen);
        } catch (RuntimeException e) {
            log.debug("[INTELLIGENCE] triage record for {} skipped: {}", complaintRef, e.getMessage());
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("model", intelligence.latestModel(Intelligence.TRIAGE));
        s.put("live", jdbc.queryForMap("SELECT COUNT(*) AS suggested, COALESCE(SUM(label = outcome), 0) AS agreed FROM ai_predictions WHERE feature = ? AND outcome IS NOT NULL "
                + "AND created_at >= NOW() - INTERVAL 30 DAY", Intelligence.TRIAGE));
        return s;
    }
}
