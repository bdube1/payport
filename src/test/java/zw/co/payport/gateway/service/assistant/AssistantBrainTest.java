package zw.co.payport.gateway.service.assistant;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Payport Assistant answers the questions people really ask, in their own words, without any AI service. */
class AssistantBrainTest {

    static final AssistantBrain BRAIN = new AssistantBrain();

    /** question as a customer types it → words the right answer's question contains */
    static final String[][] CASES = {
            {"how do i buy airtime", "buy airtime"},
            {"recharge my phone", "buy airtime"},
            {"I want to send airtime to my mother in Bulawayo", "buy airtime"},
            {"how to buy data bundles", "data bundles"},
            {"whatsapp bundle for netone", "data bundles"},
            {"buy zesa token", "ZESA"},
            {"electricity units", "ZESA"},
            {"ndingatenga sei magetsi", "ZESA"},
            {"meter number", "ZESA"},
            {"what is the ussd code", "without internet"},
            {"i dont have a smartphone", "without internet"},
            {"*699#", "without internet"},
            {"which payment methods do you accept", "How do I pay"},
            {"do you take innbucks", "How do I pay"},
            {"can i pay with visa", "How do I pay"},
            {"money was deducted but no airtime", "deducted"},
            {"mari yangu yabviswa asi handina kuwana airtime", "deducted"},
            {"I paid for zesa but didnt receive the token", "deducted"},
            {"my payment failed", "failed"},
            {"it says insufficient funds", "failed"},
            {"i didnt get the prompt on my phone", "prompt"},
            {"no ecocash popup", "prompt"},
            {"how do i check my payment status", "check a payment"},
            {"track my transaction", "check a payment"},
            {"how long does a refund take", "refunds"},
            {"I want my money back", "refunds"},
            {"I sent airtime to the wrong number", "wrong number"},
            {"how do i complain", "complaint"},
            {"what is the payport wallet", "Payport wallet"},
            {"how do i top up my wallet", "top up my wallet"},
            {"deposit money into wallet", "top up my wallet"},
            {"send money to my brother", "send money"},
            {"transfer to another wallet", "send money"},
            {"how do i withdraw cash", "cash out"},
            {"check my balance", "balance"},
            {"download statement", "statement"},
            {"exchange rate usd to zig", "convert"},
            {"increase my limit", "wallet limits"},
            {"my wallet is frozen", "frozen"},
            {"i forgot my pin", "forgot my PIN"},
            {"reset password", "forgot my PIN"},
            {"change my pin", "change my PIN"},
            {"too many attempts account locked", "locked"},
            {"is payport safe", "safe"},
            {"someone called asking for my pin", "asked for my PIN"},
            {"what is payport store", "Payport Store"},
            {"chat with the seller", "contact a seller"},
            {"where is my order", "track my store order"},
            {"my order never arrived", "did not arrive"},
            {"how do i rate a store", "rate a store"},
            {"i want to sell my products online", "open a store"},
            {"how can my business accept payments", "accept payments"},
            {"what documents do i need to register as a merchant", "register as a merchant"},
            {"when do merchants get paid", "merchants get paid"},
            {"api integration for my website", "integrate"},
            {"how do i become an agent", "agent"},
            {"what is your phone number", "contact Payport"},
            {"i want to talk to a human", "contact Payport"},
            {"are you open on sunday", "working hours"},
            {"how much are your charges", "charges"},
            {"what are the limits", "limits"},
            {"which networks", "networks"},
            {"how do i register", "register"},
            {"do i need an account", "need an account"},
            {"whatsapp number", "WhatsApp"},
            {"which bills can i pay", "bills"},
            {"pay nyaradzo", "bills"},
            {"pay a shop with qr code", "shop or business"},
            {"i approved the payment late", "late"},
            {"ecocsh not working", "prompt"},
            {"what currencies do you use", "currencies"},
            {"download the app", "download"},
            {"what is payport", "What is Payport"},
            {"was my payment successful", "successful"},
    };

    @Test
    void answersTheQuestionsPeopleAsk() {
        assertTrue(BRAIN.size() >= 60, "answers indexed: " + BRAIN.size());
        List<String> wrong = new ArrayList<>();
        for (String[] c : CASES) {
            Optional<AssistantBrain.Match> m = BRAIN.answer(c[0]);
            if (m.isEmpty() || !m.get().entry().question().toLowerCase().contains(c[1].toLowerCase())) {
                wrong.add(c[0] + " → " + m.map(x -> x.entry().question() + " (" + String.format("%.1f", x.score()) + ")").orElse("no answer"));
            }
        }
        assertTrue(wrong.isEmpty(), wrong.size() + "/" + CASES.length + " wrong:\n" + String.join("\n", wrong));
    }

    @Test
    void doesNotClaimAnAnswerForUnrelatedQuestions() {
        for (String q : List.of("what is the capital of france", "who won the football yesterday", "asdkjh qwe"))
            assertTrue(BRAIN.answer(q).isEmpty(), q + " → " + BRAIN.answer(q).map(m -> m.entry().question()).orElse(""));
    }

    @Test
    void typosAreCorrected() {
        assertEquals(0, AssistantBrain.distance("ecocash", "ecocash", 1));
        assertEquals(1, AssistantBrain.distance("ecocsh", "ecocash", 1));
        assertEquals(1, AssistantBrain.distance("walelt", "wallet", 1));
        assertTrue(BRAIN.answer("how do i top up my walelt").isPresent());
    }
}
