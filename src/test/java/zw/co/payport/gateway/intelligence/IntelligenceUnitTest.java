package zw.co.payport.gateway.intelligence;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The models on their own: triage from the catalogue alone, learning from logged complaints, and the shadow score. */
class IntelligenceUnitTest {

    static List<Object> top3(ComplaintTriage.Model m, String text) {
        return ComplaintTriage.rank(m, text, 3).stream().map(x -> x.get("category")).map(Object.class::cast).toList();
    }

    @Test
    void triage_fromTheCatalogueAlone_putsTheRightTypeInTheTopThree() {
        ComplaintTriage.Model m = ComplaintTriage.train(1, ComplaintTriage.seed());
        assertTrue(top3(m, "I was charged twice for one purchase at the supermarket").contains("M_CHARGED_TWICE"));
        assertTrue(top3(m, "The agent did not give me cash but my wallet was debited").contains("A_CASH_OUT_NO_CASH"));
        assertTrue(top3(m, "Someone withdrew money from my wallet without my authorisation at an agent").contains("A_UNAUTHORISED"));
        List<Map<String, Object>> r = ComplaintTriage.rank(m, "charged twice", 3);
        double sum = 0;
        for (Map<String, Object> x : ComplaintTriage.rank(m, "charged twice", 100)) sum += ((Number) x.get("confidence")).doubleValue();
        assertEquals(1.0, sum, 0.05, "confidences add up to one");
        assertNotNull(r.get(0).get("priority"));
    }

    @Test
    void triage_learnsTheWordsPeopleActuallyUse() {
        List<ComplaintTriage.Doc> docs = new ArrayList<>(ComplaintTriage.seed());
        for (int i = 0; i < 15; i++) docs.add(new ComplaintTriage.Doc("M_SETTLEMENT_NOT_RECEIVED", ComplaintTriage.tokens("my shop money never reached the bank account this week")));
        ComplaintTriage.Model m = ComplaintTriage.train(2, docs);
        assertEquals("M_SETTLEMENT_NOT_RECEIVED", ComplaintTriage.rank(m, "shop money did not reach my bank account", 1).get(0).get("category"));
    }

    @Test
    void tokens_dropNumbersAndCommonWords() {
        assertEquals(List.of("money", "sent", "wrong", "number"), ComplaintTriage.tokens("The money was sent to the wrong number 0771234567"));
    }

    @Test
    void shadowScore_usualPaymentsLow_unusualOnesHigh_withReasons() {
        FraudShadow.Features usual = new FraudShadow.Features(0.2, 1, 0, false, false, 20);
        FraudShadow.Features odd = new FraudShadow.Features(5.0, 6, 3, true, true, 20);
        assertEquals("LOW", FraudShadow.band(FraudShadow.score(usual)));
        assertEquals("HIGH", FraudShadow.band(FraudShadow.score(odd)));
        assertTrue(FraudShadow.reasons(odd).contains("amount unusual for this payer"));
        assertTrue(FraudShadow.reasons(usual).isEmpty());
        assertTrue(FraudShadow.score(odd) <= 1 && FraudShadow.score(usual) >= 0);
    }

    @Test
    void rates_handleNoData() {
        assertNull(FraudShadow.rates(0, 0, 0).get("precision"));
        assertEquals(0.5, FraudShadow.rates(1, 2, 4).get("precision"));
        assertEquals(0.25, FraudShadow.rates(1, 2, 4).get("recall"));
    }
}
