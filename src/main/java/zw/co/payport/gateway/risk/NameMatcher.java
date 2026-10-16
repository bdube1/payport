package zw.co.payport.gateway.risk;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fuzzy name matching for screening. Names are normalised (case, accents, punctuation, titles and
 * company suffixes removed) and compared token by token with Jaro-Winkler: every token of the
 * LIST name must be found in the subject's name, and the score is the average of the best
 * similarities. Spelling variants (JON SMYTH ~ JOHN SMITH) score high, which is intended: a
 * screening system prefers a false positive (cleared by a person, then suppressed) to a miss.
 */
public final class NameMatcher {

    private static final Set<String> NOISE = Set.of("MR", "MRS", "MS", "MISS", "DR", "PROF", "THE", "OF", "AND", "PVT", "PRIVATE",
            "LTD", "LIMITED", "INC", "LLC", "CO", "COMPANY", "TA", "PLC");

    private NameMatcher() {}

    public static List<String> tokens(String name) {
        if (name == null) return List.of();
        String s = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9 ]", " ");
        List<String> out = new ArrayList<>();
        for (String t : s.trim().split("\\s+")) if (!t.isEmpty() && !NOISE.contains(t)) out.add(t);
        return out;
    }

    /** 0..1: how well the subject's name contains the listed name. */
    public static double score(String subjectName, String listedName) {
        List<String> subject = tokens(subjectName), listed = tokens(listedName);
        if (subject.isEmpty() || listed.isEmpty()) return 0;
        double sum = 0;
        for (String l : listed) {
            double best = 0;
            for (String s : subject) best = Math.max(best, jaroWinkler(l, s));
            sum += best;
        }
        double avg = sum / listed.size();
        // A one-word listed name against a long subject name is weak evidence.
        if (listed.size() == 1 && subject.size() > 2) avg *= 0.95;
        return Math.round(avg * 1000) / 1000.0;
    }

    public static double jaroWinkler(String a, String b) {
        if (a.equals(b)) return 1.0;
        int la = a.length(), lb = b.length();
        if (la == 0 || lb == 0) return 0;
        int window = Math.max(0, Math.max(la, lb) / 2 - 1);
        boolean[] ma = new boolean[la], mb = new boolean[lb];
        int matches = 0;
        for (int i = 0; i < la; i++) {
            for (int j = Math.max(0, i - window); j < Math.min(lb, i + window + 1); j++) {
                if (!mb[j] && a.charAt(i) == b.charAt(j)) { ma[i] = mb[j] = true; matches++; break; }
            }
        }
        if (matches == 0) return 0;
        int t = 0, k = 0;
        for (int i = 0; i < la; i++) {
            if (!ma[i]) continue;
            while (!mb[k]) k++;
            if (a.charAt(i) != b.charAt(k)) t++;
            k++;
        }
        double m = matches;
        double jaro = (m / la + m / lb + (m - t / 2.0) / m) / 3.0;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(la, lb)) && a.charAt(i) == b.charAt(i); i++) prefix++;
        return jaro + prefix * 0.1 * (1 - jaro);
    }

    /** Phone numbers compare on their last 9 digits; other identifiers upper-cased without separators. */
    public static String identifier(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("\\D", "");
        if (raw.trim().matches("[+0-9 ()-]{9,}") && digits.length() >= 9) return "TEL:" + digits.substring(digits.length() - 9);
        String id = raw.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return id.isEmpty() ? null : "ID:" + id;
    }
}
