package zw.co.payport.gateway.psp.risk;

import java.text.Normalizer;
import java.util.*;

/**
 * Fuzzy name matching for sanctions / PEP screening. Pure and unit-tested.
 *
 * Names are normalised (accents stripped, punctuation removed, honorifics
 * dropped, upper-cased) and compared token-by-token with Jaro-Winkler, so
 * "Mugabe, Robert G." matches "ROBERT GABRIEL MUGABE" and transliteration
 * variants ("Mohammed" / "Muhammad") still score highly. Token order is
 * ignored. The score is 0-100.
 */
public final class NameMatcher {

    private static final Set<String> STOP = Set.of("MR", "MRS", "MS", "DR", "PROF", "HON", "SIR", "THE", "OF", "AND",
            "PVT", "LTD", "LIMITED", "PRIVATE", "INC", "CO", "COMPANY", "T/A", "TA", "BIN", "BINTI", "AL", "EL");

    private NameMatcher() {}

    public static String normalise(String name) {
        if (name == null) return "";
        String n = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        n = n.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", " ").replaceAll("\\s+", " ").trim();
        List<String> tokens = new ArrayList<>();
        for (String t : n.split(" ")) if (!t.isEmpty() && !STOP.contains(t)) tokens.add(t);
        Collections.sort(tokens);
        return String.join(" ", tokens);
    }

    /**
     * Score of the candidate against a listed name. Every token of the shorter
     * name must find a good partner in the longer one; initials match a token
     * starting with that letter at a reduced weight.
     */
    public static int score(String candidate, String listed) {
        List<String> a = tokens(normalise(candidate));
        List<String> b = tokens(normalise(listed));
        if (a.isEmpty() || b.isEmpty()) return 0;
        List<String> shorter = a.size() <= b.size() ? a : b;
        List<String> longer = a.size() <= b.size() ? b : a;
        double total = 0;
        boolean[] used = new boolean[longer.size()];
        for (String s : shorter) {
            double best = 0;
            int bestIdx = -1;
            for (int i = 0; i < longer.size(); i++) {
                if (used[i]) continue;
                String l = longer.get(i);
                double sim = (s.length() == 1 || l.length() == 1)
                        ? (s.charAt(0) == l.charAt(0) ? 0.85 : 0)
                        : jaroWinkler(s, l);
                if (sim > best) { best = sim; bestIdx = i; }
            }
            if (bestIdx >= 0) used[bestIdx] = true;
            total += best;
        }
        double avg = total / shorter.size();
        // Penalise very partial matches (one token of a three-token name) a little.
        double coverage = (double) shorter.size() / longer.size();
        double s = avg * (0.85 + 0.15 * coverage);
        if (shorter.size() == 1 && longer.size() > 1) s *= 0.8;
        return (int) Math.round(s * 100);
    }

    /** Best score across the primary name and its aliases. */
    public static int bestScore(String candidate, String primary, Collection<String> aliases) {
        int best = score(candidate, primary);
        if (aliases != null) for (String al : aliases) best = Math.max(best, score(candidate, al));
        return best;
    }

    private static List<String> tokens(String normalised) {
        return normalised.isEmpty() ? List.of() : Arrays.asList(normalised.split(" "));
    }

    public static double jaroWinkler(String s1, String s2) {
        if (s1.equals(s2)) return 1.0;
        int len1 = s1.length(), len2 = s2.length();
        int matchDistance = Math.max(0, Math.max(len1, len2) / 2 - 1);
        boolean[] m1 = new boolean[len1], m2 = new boolean[len2];
        int matches = 0;
        for (int i = 0; i < len1; i++) {
            int start = Math.max(0, i - matchDistance), end = Math.min(i + matchDistance + 1, len2);
            for (int j = start; j < end; j++) {
                if (m2[j] || s1.charAt(i) != s2.charAt(j)) continue;
                m1[i] = true;
                m2[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) return 0;
        double t = 0;
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (!m1[i]) continue;
            while (!m2[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) t++;
            k++;
        }
        t /= 2;
        double jaro = ((double) matches / len1 + (double) matches / len2 + (matches - t) / matches) / 3.0;
        int prefix = 0;
        for (int i = 0; i < Math.min(4, Math.min(len1, len2)); i++) {
            if (s1.charAt(i) == s2.charAt(i)) prefix++;
            else break;
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }
}
