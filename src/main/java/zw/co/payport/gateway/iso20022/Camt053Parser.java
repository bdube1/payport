package zw.co.payport.gateway.iso20022;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * camt.053.001.08 (Bank to Customer Statement) for one of PayPort's own bank accounts.
 * Canonical mapping per statement (BkToCstmrStmt/Stmt):
 *
 *   Stmt/Id                         -> statement id (with the account, the import key)
 *   Acct/Id (IBAN or Othr/Id), Ccy  -> must be the market's bank account for that currency
 *   Bal Tp OPBD / CLBD              -> opening / closing booked balance (sign from CdtDbtInd)
 *   Ntry Amt, CdtDbtInd             -> entry amount and direction
 *   Ntry Sts (BOOK, PDNG, INFO)     -> only BOOK entries count towards the balance check and tracing
 *   Ntry BookgDt / ValDt            -> booking and value dates
 *   Ntry AcctSvcrRef (or TxDtls/Refs/AcctSvcrRef, as in HVPS+) -> the bank's reference
 *   TxDtls/Refs/EndToEndId          -> PayPort's payout instruction_ref for outgoing payouts
 *   TxDtls/RltdPties Cdtr or Dbtr   -> counterparty name; RmtInf/Ustrd -> remittance text
 */
public final class Camt053Parser {

    public record Entry(int no, String direction, long amountMinor, String currency, String status, LocalDate bookingDate, LocalDate valueDate,
                        String bankReference, String endToEndId, String counterparty, String remittance) {}

    public record Statement(String msgId, String statementId, String accountId, String currency, LocalDateTime from, LocalDateTime to,
                            long openingMinor, long closingMinor, List<Entry> entries) {
        public long bookedCredits() {
            return entries.stream().filter(e -> "BOOK".equals(e.status()) && "CRDT".equals(e.direction())).mapToLong(Entry::amountMinor).sum();
        }

        public long bookedDebits() {
            return entries.stream().filter(e -> "BOOK".equals(e.status()) && "DBIT".equals(e.direction())).mapToLong(Entry::amountMinor).sum();
        }
    }

    private Camt053Parser() {}

    public static List<Statement> parse(Document doc, int minorUnits) {
        Element root = Xml.child(doc.getDocumentElement(), "BkToCstmrStmt");
        if (root == null) throw new Iso20022Schemas.InvalidMessageException("not a BkToCstmrStmt");
        String msgId = Xml.text(root, "GrpHdr", "MsgId");
        List<Statement> out = new ArrayList<>();
        for (Element st : Xml.children(root, "Stmt")) {
            Element acct = Xml.child(st, "Acct");
            String accountId = Xml.text(acct, "Id", "IBAN");
            if (accountId == null) accountId = Xml.text(acct, "Id", "Othr", "Id");
            String ccy = Xml.text(acct, "Ccy");
            Long opening = null, closing = null;
            for (Element bal : Xml.children(st, "Bal")) {
                String type = Xml.text(bal, "Tp", "CdOrPrtry", "Cd");
                Element amt = Xml.child(bal, "Amt");
                if (ccy == null) ccy = amt.getAttribute("Ccy");
                long v = Xml.minor(amt.getTextContent(), minorUnits) * ("DBIT".equals(Xml.text(bal, "CdtDbtInd")) ? -1 : 1);
                if ("OPBD".equals(type) || ("PRCD".equals(type) && opening == null)) opening = v;
                if ("CLBD".equals(type)) closing = v;
            }
            if (opening == null || closing == null) {
                throw new Iso20022Schemas.InvalidMessageException("Statement " + Xml.text(st, "Id") + " has no opening (OPBD) and closing (CLBD) booked balance");
            }
            List<Entry> entries = new ArrayList<>();
            int n = 0;
            for (Element ntry : Xml.children(st, "Ntry")) {
                n++;
                Element amt = Xml.child(ntry, "Amt");
                String status = Xml.text(ntry, "Sts", "Cd");
                if (status == null) status = Xml.text(ntry, "Sts", "Prtry");
                Element tx = Xml.path(ntry, "NtryDtls", "TxDtls");
                String direction = Xml.text(ntry, "CdtDbtInd");
                String party = "DBIT".equals(direction) ? Xml.text(tx, "RltdPties", "Cdtr", "Pty", "Nm") : Xml.text(tx, "RltdPties", "Dbtr", "Pty", "Nm");
                entries.add(new Entry(n, direction, Xml.minor(amt.getTextContent(), minorUnits), amt.getAttribute("Ccy"), status,
                        date(Xml.child(ntry, "BookgDt")), date(Xml.child(ntry, "ValDt")),
                        Xml.text(ntry, "AcctSvcrRef") != null ? Xml.text(ntry, "AcctSvcrRef") : Xml.text(tx, "Refs", "AcctSvcrRef"),
                        Xml.text(tx, "Refs", "EndToEndId"), party, Xml.text(tx, "RmtInf", "Ustrd")));
            }
            out.add(new Statement(msgId, Xml.text(st, "Id"), accountId, ccy, dateTime(Xml.text(st, "FrToDt", "FrDtTm")),
                    dateTime(Xml.text(st, "FrToDt", "ToDtTm")), opening, closing, entries));
        }
        return out;
    }

    private static LocalDate date(Element choice) {
        if (choice == null) return null;
        String d = Xml.text(choice, "Dt");
        if (d != null) return LocalDate.parse(d.length() > 10 ? d.substring(0, 10) : d);
        String dt = Xml.text(choice, "DtTm");
        return dt == null ? null : dateTime(dt).toLocalDate();
    }

    private static LocalDateTime dateTime(String s) {
        if (s == null) return null;
        try {
            return OffsetDateTime.parse(s).toLocalDateTime();
        } catch (Exception e) {
            return LocalDateTime.parse(s);
        }
    }
}
