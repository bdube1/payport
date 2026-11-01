package zw.co.payport.gateway.service.assistant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Payport Assistant's own answer engine: it answers from the knowledge base without any AI service, so the assistant
 * always answers, and it picks the passages the AI is given when one is switched on.
 *
 * Every "**Q: ...**" in assistant-kb/*.md is an entry, with its "_Also: ..._" phrasings. A question is matched with
 * BM25 over the entry's phrasings (weighted most), its heading and its answer, after:
 *  - normalising (case, accents, punctuation) and dropping filler words;
 *  - mapping synonyms and common Shona and Ndebele words onto the knowledge base's own words
 *    (recharge → airtime, magetsi → zesa, mari → money, ...);
 *  - light stemming (payments → payment, buying → buy);
 *  - correcting typos against the knowledge base's vocabulary (ecocsh → ecocash);
 *  - a bonus for phrasings whose word pairs appear in the question ("money deducted").
 * Below a confidence threshold no entry is claimed to be the answer.
 */
@Slf4j
@Component
public class AssistantBrain {

    public record Entry(String question, List<String> also, String answer, String section) {}
    public record Match(Entry entry, double score) {}

    private static final Pattern QA = Pattern.compile("\\*\\*Q:\\s*(.+?)\\*\\*\\s*\\n(.*?)(?=\\n\\s*\\n\\*\\*Q:|\\n\\s*\\n#|\\z)", Pattern.DOTALL);
    private static final Pattern ALSO = Pattern.compile("(?m)^_Also:\\s*(.+?)_\\s*$");

    static final Set<String> STOP = Set.of("a", "an", "the", "i", "me", "my", "mine", "you", "your", "we", "our", "us", "it", "its", "is", "am",
            "are", "was", "were", "be", "been", "do", "does", "did", "to", "of", "in", "on", "for", "at", "by", "with", "and", "or", "but",
            "so", "if", "this", "that", "these", "those", "there", "here", "what", "which", "who", "whom", "how", "when", "where", "why",
            "can", "could", "would", "should", "will", "shall", "may", "might", "must", "please", "pls", "plz", "hi", "hello", "hey", "hie",
            "just", "about", "from", "into", "as", "any", "some", "get", "got", "have", "has", "had", "want", "know", "tell", "use",
            "using", "via", "then", "also", "too", "very", "really", "kindly", "help", "ok", "okay", "yes",
            "im", "ive", "id", "still", "again", "now", "today", "one", "someone",
            "sir", "madam", "maam", "guys", "team", "assist", "question", "like", "way", "go", "going");

    /** Words people use → the knowledge base's words (English, slang, Shona and Ndebele). */
    static final Map<String, String> SYN = new HashMap<>();
    static {
        String[][] pairs = {
                {"recharge", "airtime"}, {"credit", "airtime"}, {"airtyme", "airtime"}, {"atime", "airtime"}, {"talktime", "airtime"},
                {"electricity", "zesa"}, {"magetsi", "zesa"}, {"ugesi", "zesa"}, {"power", "zesa"}, {"units", "zesa"}, {"prepaid", "zesa"},
                {"tokens", "token"}, {"meter", "zesa"},
                {"bundle", "data"}, {"bundles", "data"}, {"internet", "data"}, {"mb", "data"}, {"gb", "data"},
                {"withdraw", "cashout"}, {"withdrawal", "cashout"}, {"kubvisa", "cashout"},
                {"deposit", "topup"}, {"load", "topup"}, {"fund", "topup"}, {"kuisa", "topup"},
                {"transfer", "send"}, {"p2p", "send"}, {"kutumira", "send"}, {"thumela", "send"},
                {"mari", "money"}, {"imali", "money"}, {"funds", "money"}, {"cash", "cash"},
                {"kutenga", "buy"}, {"ukuthenga", "buy"}, {"purchase", "buy"}, {"purchased", "buy"}, {"bought", "buy"},
                {"deducted", "deducted"}, {"debited", "deducted"}, {"taken", "deducted"}, {"charged", "deducted"}, {"yabviswa", "deducted"},
                {"yabuda", "deducted"}, {"cut", "deducted"},
                {"declined", "failed"}, {"unsuccessful", "failed"}, {"yakaramba", "failed"}, {"failing", "failed"}, {"fail", "failed"},
                {"error", "failed"}, {"rejected", "failed"},
                {"password", "pin"}, {"passcode", "pin"},
                {"signup", "register"}, {"enroll", "register"}, {"enrol", "register"}, {"kunyoresa", "register"}, {"join", "register"},
                {"app", "app"}, {"application", "app"}, {"apk", "app"},
                {"shop", "store"}, {"shops", "store"}, {"marketplace", "store"}, {"seller", "seller"}, {"vendor", "seller"},
                {"business", "merchant"}, {"company", "merchant"}, {"till", "merchant"}, {"merchants", "merchant"},
                {"fee", "charge"}, {"fees", "charge"}, {"cost", "charge"}, {"costs", "charge"}, {"commission", "charge"},
                {"refunded", "refund"}, {"reversal", "refund"}, {"reverse", "refund"}, {"reversed", "refund"}, {"chargeback", "refund"},
                {"zig", "zwg"}, {"rtgs", "zwg"}, {"bond", "zwg"}, {"dollar", "usd"}, {"dollars", "usd"}, {"us", "usd"},
                {"ecocsh", "ecocash"}, {"eco", "ecocash"}, {"ecocah", "ecocash"}, {"one", "one"}, {"omari", "omari"}, {"o'mari", "omari"},
                {"innbuck", "innbucks"}, {"inbucks", "innbucks"}, {"zimswitch", "card"}, {"visa", "card"}, {"mastercard", "card"},
                {"debit", "card"}, {"bank", "bank"},
                {"popup", "prompt"}, {"pop", "prompt"}, {"push", "prompt"},
                {"complain", "complaint"}, {"dispute", "complaint"}, {"escalate", "complaint"},
                {"hours", "hours"}, {"open", "open"},
                {"safe", "safe"}, {"secure", "safe"}, {"legit", "safe"}, {"scam", "scam"}, {"fraud", "scam"}, {"scammer", "scam"},
                {"phishing", "scam"},
                {"wallet", "wallet"}, {"ewallet", "wallet"}, {"account", "account"},
                {"statement", "statement"}, {"history", "statement"}, {"transactions", "statement"},
                {"track", "check"}, {"trace", "check"}, {"status", "check"}, {"confirm", "check"},
                {"rate", "rate"}, {"rating", "rate"}, {"review", "rate"}, {"stars", "rate"},
                {"code", "code"}, {"ussd", "ussd"}, {"shortcode", "ussd"}, {"699", "ussd"}, {"dial", "ussd"},
                {"whatsap", "whatsapp"}, {"watsapp", "whatsapp"}, {"wa", "whatsapp"},
                {"contact", "contact"}, {"call", "contact"}, {"phone", "phone"}, {"email", "contact"}, {"support", "contact"},
                {"human", "contact"}, {"person", "contact"}, {"customer", "customer"},
                {"limits", "limit"}, {"maximum", "limit"}, {"max", "limit"}, {"minimum", "limit"}, {"min", "limit"},
                {"kyc", "verify"}, {"verification", "verify"}, {"verified", "verify"}, {"upgrade", "verify"}, {"tier", "verify"},
                {"frozen", "frozen"}, {"freeze", "frozen"}, {"blocked", "frozen"}, {"suspended", "frozen"},
                {"locked", "locked"}, {"lock", "locked"},
                {"exchange", "convert"}, {"forex", "convert"},
                {"nyaradzo", "nyaradzo"}, {"moonlight", "moonlight"}, {"zol", "zol"}, {"dstv", "bill"}, {"bills", "bill"}, {"billers", "bill"},
                {"biller", "bill"},
                {"qr", "qr"}, {"scan", "qr"},
                {"api", "api"}, {"integrate", "api"}, {"integration", "api"}, {"developer", "api"}, {"webhook", "api"}, {"plugin", "api"},
                {"settlement", "payout"}, {"settled", "payout"}, {"payouts", "payout"},
                {"agents", "agent"}, {"float", "agent"},
                {"timeout", "late"}, {"timed", "late"}, {"expired", "late"}, {"delay", "late"}, {"delayed", "late"},
                {"wrong", "wrong"}, {"mistake", "wrong"}, {"mistakenly", "wrong"},
                {"forgot", "forgot"}, {"forgotten", "forgot"}, {"lost", "forgot"}, {"reset", "forgot"},
                {"received", "receive"}, {"recieve", "receive"}, {"recieved", "receive"}, {"arrive", "receive"}, {"arrived", "receive"},
                {"delivered", "receive"}, {"delivery", "delivery"},
                {"chii", "what"}, {"no", "not"}, {"dont", "not"}, {"didnt", "not"}, {"doesnt", "not"}, {"cant", "not"}, {"cannot", "not"},
                {"wont", "not"}, {"never", "not"}, {"havent", "not"}, {"hasnt", "not"}, {"isnt", "not"}, {"wasnt", "not"}, {"handina", "not"},
                {"asking", "ask"}, {"asked", "ask"}, {"asks", "ask"}, {"helpline", "contact"}, {"paying", "pay"}, {"chinonzi", "what"}, {"sei", "how"}, {"ndingaite", "how"}, {"ndinga", "how"},
        };
        for (String[] p : pairs) SYN.put(p[0], p[1]);
    }

    /** Built per entry: its tokens per field, for BM25. */
    private record Doc(Entry entry, List<String> q, List<String> body, Set<String> qBigrams) {}

    private final List<Doc> docs = new ArrayList<>();
    private final Map<String, Integer> df = new HashMap<>();
    private final Set<String> vocab = new HashSet<>();
    private double avgQ = 1, avgBody = 1;

    public AssistantBrain() {
        try {
            Resource[] files = new PathMatchingResourcePatternResolver().getResources("classpath:assistant-kb/*.md");
            Arrays.sort(files, Comparator.comparing(Resource::getFilename));
            List<String> texts = new ArrayList<>();
            for (Resource r : files) texts.add(new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            load(texts);
        } catch (Exception e) {
            log.error("[ASSISTANT] knowledge base not indexed: {}", e.getMessage());
        }
    }

    /** For tests: an engine over the given knowledge base texts. */
    AssistantBrain(List<String> texts) {
        load(texts);
    }

    private void load(List<String> texts) {
        for (String text : texts) {
            String t = text.replace("\r\n", "\n");
            Matcher m = QA.matcher(t);
            while (m.find()) {
                String question = m.group(1).trim();
                String raw = m.group(2).trim();
                List<String> also = new ArrayList<>();
                Matcher a = ALSO.matcher(raw);
                while (a.find()) for (String s : a.group(1).split("\\|")) if (!s.isBlank()) also.add(s.trim());
                String answer = ALSO.matcher(raw).replaceAll("").trim().replaceAll("\\n(?!\\n|-|\\d+\\.)", " ").replaceAll(" {2,}", " ");
                String section = sectionOf(t, m.start());
                Entry e = new Entry(question, also, answer, section);
                List<String> q = new ArrayList<>(tokens(question));
                for (String s : also) q.addAll(tokens(s));
                List<String> body = new ArrayList<>(tokens(section));
                body.addAll(tokens(answer));
                Set<String> bigrams = new HashSet<>(bigrams(tokens(question)));
                for (String s : also) bigrams.addAll(bigrams(tokens(s)));
                docs.add(new Doc(e, q, body, bigrams));
            }
        }
        for (Doc d : docs) {
            Set<String> seen = new HashSet<>(d.q());
            seen.addAll(d.body());
            for (String w : seen) df.merge(w, 1, Integer::sum);
            vocab.addAll(seen);
        }
        avgQ = docs.stream().mapToInt(d -> d.q().size()).average().orElse(1);
        avgBody = docs.stream().mapToInt(d -> d.body().size()).average().orElse(1);
        log.info("[ASSISTANT] {} answers indexed, {} words", docs.size(), vocab.size());
    }

    private static String sectionOf(String text, int at) {
        int h = text.lastIndexOf("\n## ", at);
        if (h < 0) return "";
        int end = text.indexOf('\n', h + 1);
        return text.substring(h + 4, end < 0 ? text.length() : end).trim();
    }

    public int size() {
        return docs.size();
    }

    /** The best entries for a question, best first, with their scores. */
    public List<Match> search(String question, int k) {
        List<String> qt = correct(tokens(question));
        if (qt.isEmpty()) return List.of();
        Set<String> qb = new HashSet<>(bigrams(qt));
        int n = docs.size();
        List<Match> out = new ArrayList<>();
        for (Doc d : docs) {
            double s = 0;
            for (String w : new LinkedHashSet<>(qt)) {
                Integer f = df.get(w);
                if (f == null) continue;
                double idf = Math.log(1 + (n - f + 0.5) / (f + 0.5));
                s += 2.2 * idf * bm25(count(d.q(), w), d.q().size(), avgQ) + idf * bm25(count(d.body(), w), d.body().size(), avgBody);
            }
            for (String b : qb) if (d.qBigrams().contains(b)) s += 1.5;
            if (s > 0) out.add(new Match(d.entry(), s));
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return out.size() > k ? out.subList(0, k) : out;
    }

    /** The answer when one entry clearly fits; empty when nothing fits well enough. */
    public Optional<Match> answer(String question) {
        List<String> asked = correct(tokens(question));
        // asked exactly as written in the knowledge base (for example "what is payport")
        for (Doc d : docs) if (asked.equals(tokens(d.entry().question()))) return Optional.of(new Match(d.entry(), 99));
        List<Match> top = search(question, 8);
        if (top.isEmpty()) return Optional.empty();
        for (Match m : top) for (String a : m.entry().also()) if (asked.equals(tokens(a))) return Optional.of(new Match(m.entry(), 99));
        int words = asked.size();
        double need = words <= 1 ? 3.0 : 4.0;
        return top.get(0).score() >= need ? Optional.of(top.get(0)) : Optional.empty();
    }

    private static double bm25(int tf, int len, double avg) {
        if (tf == 0) return 0;
        double k1 = 1.2, b = 0.6;
        return tf * (k1 + 1) / (tf + k1 * (1 - b + b * len / avg));
    }

    private static int count(List<String> l, String w) {
        int c = 0;
        for (String x : l) if (x.equals(w)) c++;
        return c;
    }

    private static List<String> bigrams(List<String> t) {
        List<String> b = new ArrayList<>();
        for (int i = 0; i + 1 < t.size(); i++) b.add(t.get(i) + " " + t.get(i + 1));
        return b;
    }

    /** Words not in the knowledge base are replaced by the closest one (one or two letters off). */
    List<String> correct(List<String> words) {
        List<String> out = new ArrayList<>();
        for (String w : words) {
            if (vocab.isEmpty() || vocab.contains(w) || w.length() < 4 || w.chars().allMatch(Character::isDigit)) { out.add(w); continue; }
            int max = w.length() >= 8 ? 2 : 1;
            String best = null;
            int bestD = max + 1;
            for (String v : vocab) {
                if (Math.abs(v.length() - w.length()) > max) continue;
                int d = distance(w, v, max);
                if (d < bestD || (d == bestD && best != null && df.getOrDefault(v, 0) > df.getOrDefault(best, 0))) { bestD = d; best = v; }
            }
            out.add(best != null && bestD <= max ? best : w);
        }
        return out;
    }

    /** Damerau-Levenshtein distance (adjacent swaps count as one), stopping early beyond max. */
    static int distance(String a, String b, int max) {
        int n = a.length(), m = b.length();
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            int rowMin = Integer.MAX_VALUE;
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                rowMin = Math.min(rowMin, d[i][j]);
            }
            if (rowMin > max) return max + 1;
        }
        return d[n][m];
    }

    /** Normalised, meaningful words: lower case, no accents or punctuation, synonyms mapped, lightly stemmed. */
    static List<String> tokens(String text) {
        if (text == null) return List.of();
        String t = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        t = t.replace("o'mari", "omari").replace("top up", "topup").replace("top-up", "topup").replace("cash out", "cashout")
                .replace("cash-out", "cashout").replace("cash in", "topup").replace("one money", "onemoney").replace("e-wallet", "ewallet")
                .replace("*699#", " 699 ").replace("pay on delivery", "payondelivery").replace("cash on delivery", "payondelivery")
                .replace("school fees", "school").replace("school fee", "school");
        List<String> out = new ArrayList<>();
        for (String w : t.split("[^a-z0-9]+")) {
            if (w.isEmpty() || STOP.contains(w)) continue;
            String s = SYN.getOrDefault(w, null);
            if (s == null) s = SYN.getOrDefault(stem(w), stem(w));
            if (!STOP.contains(s)) out.add(s);
        }
        return out;
    }

    static String stem(String w) {
        if (w.length() <= 4) return w;
        if (w.endsWith("ies") && w.length() > 5) return w.substring(0, w.length() - 3) + "y";
        if (w.endsWith("ing") && w.length() > 6) return w.substring(0, w.length() - 3);
        if (w.endsWith("ed") && w.length() > 5) return w.substring(0, w.length() - 2);
        if (w.endsWith("es") && (w.endsWith("sses") || w.endsWith("ches") || w.endsWith("shes"))) return w.substring(0, w.length() - 2);
        if (w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us")) return w.substring(0, w.length() - 1);
        return w;
    }
}
