package zw.co.payport.gateway.iso20022;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import zw.co.payport.gateway.settlement.SettlementBatchService.PayoutResult;

import java.util.ArrayList;
import java.util.List;

/**
 * The bank's pain.002.001.10 (Customer Payment Status Report) for a pain.001 PayPort sent.
 * Mapping per transaction (OrgnlPmtInfAndSts/TxInfAndSts):
 *
 *   OrgnlEndToEndId (or OrgnlInstrId) -> stl_items.instruction_ref
 *   TxSts ACSC                         -> PAID, bank reference = AcctSvcrRef (PAID is refused without one)
 *   TxSts RJCT                         -> FAILED, reason = StsRsnInf/Rsn/Cd (+ AddtlInf)
 *   ACTC, ACCP, ACSP, ACWC, PDNG, RCVD -> still in progress: no change
 *
 * A group-level RJCT (OrgnlGrpInfAndSts/GrpSts) without per-transaction statuses rejects every
 * instruction of the original message.
 */
public final class Pain002Parser {

    public record Report(String msgId, String originalMsgId, List<PayoutResult> results, int inProgress) {}

    private Pain002Parser() {}

    public static Report parse(Document doc, List<String> originalInstructionRefs) {
        Element root = Xml.child(doc.getDocumentElement(), "CstmrPmtStsRpt");
        if (root == null) throw new Iso20022Schemas.InvalidMessageException("not a CstmrPmtStsRpt");
        String msgId = Xml.text(root, "GrpHdr", "MsgId");
        Element grp = Xml.child(root, "OrgnlGrpInfAndSts");
        String original = Xml.text(grp, "OrgnlMsgId");
        List<PayoutResult> out = new ArrayList<>();
        int pending = 0;
        boolean anyTx = false;
        for (Element pmt : Xml.children(root, "OrgnlPmtInfAndSts")) {
            for (Element tx : Xml.children(pmt, "TxInfAndSts")) {
                anyTx = true;
                String ref = Xml.text(tx, "OrgnlEndToEndId");
                if (ref == null || ref.isBlank() || "NOTPROVIDED".equals(ref)) ref = Xml.text(tx, "OrgnlInstrId");
                String sts = Xml.text(tx, "TxSts");
                if (ref == null || sts == null) { pending++; continue; }
                switch (sts) {
                    case "ACSC" -> out.add(new PayoutResult(ref, "PAID", Xml.text(tx, "AcctSvcrRef"), null));
                    case "RJCT" -> out.add(new PayoutResult(ref, "FAILED", null, reason(tx)));
                    default -> pending++;
                }
            }
        }
        if (!anyTx && "RJCT".equals(Xml.text(grp, "GrpSts"))) {
            String why = reason(grp);
            for (String ref : originalInstructionRefs) out.add(new PayoutResult(ref, "FAILED", null, why));
        }
        return new Report(msgId, original, out, pending);
    }

    private static String reason(Element e) {
        Element info = Xml.child(e, "StsRsnInf");
        if (info == null) return "Rejected by the bank (no reason given)";
        String code = Xml.text(info, "Rsn", "Cd");
        if (code == null) code = Xml.text(info, "Rsn", "Prtry");
        String add = Xml.text(info, "AddtlInf");
        return (code == null ? "Rejected" : code) + (add == null ? "" : ": " + add);
    }
}
