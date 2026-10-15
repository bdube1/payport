package zw.co.payport.gateway.psp.risk;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RiskLogicTest {

    private static RuleEvaluator.Rule rule(String type, int window, int count, long amount, int score, String action) {
        return new RuleEvaluator.Rule("R_" + type, type, List.of("P2P", "CASH_OUT"), window, count, amount, score, action);
    }

    @Test
    void velocityCountFiresOnlyAboveThreshold() {
        RuleEvaluator.Rule r = rule("VELOCITY_COUNT", 60, 5, 0, 30, "REVIEW");
        RuleEvaluator.Features f = new RuleEvaluator.Features("P2P", 1_000, false, LocalTime.NOON);
        assertNull(RuleEvaluator.evaluate(r, f, new RuleEvaluator.Window(4, 0, 0)));
        assertNotNull(RuleEvaluator.evaluate(r, f, new RuleEvaluator.Window(5, 0, 0)));
    }

    @Test
    void rulesOnlyApplyToTheirTransactionTypes() {
        RuleEvaluator.Rule r = rule("SINGLE_AMOUNT", 0, 0, 100, 50, "BLOCK");
        assertNull(RuleEvaluator.evaluate(r, new RuleEvaluator.Features("CASH_IN", 1_000_000, false, LocalTime.NOON), new RuleEvaluator.Window(0, 0, 0)));
        assertNotNull(RuleEvaluator.evaluate(r, new RuleEvaluator.Features("P2P", 1_000_000, false, LocalTime.NOON), new RuleEvaluator.Window(0, 0, 0)));
    }

    @Test
    void nightAndNewCounterpartyRules() {
        RuleEvaluator.Rule night = rule("NIGHT_AMOUNT", 0, 0, 10_000, 20, "REVIEW");
        assertNotNull(RuleEvaluator.evaluate(night, new RuleEvaluator.Features("P2P", 20_000, false, LocalTime.of(2, 30)), new RuleEvaluator.Window(0, 0, 0)));
        assertNull(RuleEvaluator.evaluate(night, new RuleEvaluator.Features("P2P", 20_000, false, LocalTime.of(14, 0)), new RuleEvaluator.Window(0, 0, 0)));
        RuleEvaluator.Rule fresh = rule("NEW_COUNTERPARTY_AMOUNT", 0, 0, 50_000, 25, "REVIEW");
        assertNotNull(RuleEvaluator.evaluate(fresh, new RuleEvaluator.Features("P2P", 60_000, true, LocalTime.NOON), new RuleEvaluator.Window(0, 0, 0)));
        assertNull(RuleEvaluator.evaluate(fresh, new RuleEvaluator.Features("P2P", 60_000, false, LocalTime.NOON), new RuleEvaluator.Window(0, 0, 0)));
    }

    @Test
    void combineRespectsHardBlocksAndScoreThresholds() {
        RuleEvaluator.Hit review = new RuleEvaluator.Hit("A", 30, "REVIEW", "a");
        RuleEvaluator.Hit block = new RuleEvaluator.Hit("B", 10, "BLOCK", "b");
        assertEquals("ALLOW", RuleEvaluator.combine(List.of(), 40, 80).action());
        assertEquals("REVIEW", RuleEvaluator.combine(List.of(review), 40, 80).action());
        assertEquals("BLOCK", RuleEvaluator.combine(List.of(block), 40, 80).action());
        assertEquals("BLOCK", RuleEvaluator.combine(List.of(review, review, review), 40, 80).action());
    }

    @Test
    void nameMatchingHandlesOrderPunctuationAndAccents() {
        assertTrue(NameMatcher.score("Mugabe, Robert G.", "ROBERT GABRIEL MUGABE") >= 85);
        assertTrue(NameMatcher.score("José Ñúñez", "JOSE NUNEZ") >= 98);
        assertTrue(NameMatcher.score("Mohammed Ali Hassan", "Muhammad Ali Hasan") >= 88);
        assertTrue(NameMatcher.score("Tendai Moyo", "Chipo Ncube") < 60);
        assertEquals(NameMatcher.normalise("Dr. Tendai MOYO"), NameMatcher.normalise("moyo tendai"));
    }

    @Test
    void singleTokenMatchesAreDiscounted() {
        assertTrue(NameMatcher.score("Moyo", "Tendai Farai Moyo") < 85);
    }
}
