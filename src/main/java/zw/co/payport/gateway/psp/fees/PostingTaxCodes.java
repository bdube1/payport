package zw.co.payport.gateway.psp.fees;

import zw.co.payport.gateway.psp.ledger.domain.PostingRules;

import java.util.Set;

/** Tax codes understood by the ledger (TAX_PAYABLE owner refs). */
public final class PostingTaxCodes {
    private PostingTaxCodes() {}

    public static final String VAT = PostingRules.TAX_VAT;
    public static final String IMTT = PostingRules.TAX_TRANSFER;
    public static final Set<String> ALL = Set.of(VAT, IMTT);
}
