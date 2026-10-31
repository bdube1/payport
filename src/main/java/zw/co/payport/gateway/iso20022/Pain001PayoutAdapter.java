package zw.co.payport.gateway.iso20022;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import zw.co.payport.gateway.markets.MarketRegistry;
import zw.co.payport.gateway.settlement.PayoutFileAdapter;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Settlement payout instructions as ISO 20022 pain.001.001.09 (Customer Credit Transfer
 * Initiation). Canonical mapping:
 *
 *   stl_batches.batch_ref        -> GrpHdr/MsgId and PmtInf/PmtInfId
 *   batch created_at             -> GrpHdr/CreDtTm; ReqdExctnDt is the day the file is generated
 *                                   (the file is stored, so re-instructing returns it unchanged)
 *   item count, sum of net       -> NbOfTxs, CtrlSum (group and payment information)
 *   market bank account          -> Dbtr, DbtrAcct (Othr/Id), DbtrAgt (BICFI or "NOTPROVIDED")
 *   stl_items.instruction_ref    -> CdtTrfTxInf/PmtId/InstrId and EndToEndId (returned by the bank in
 *                                   pain.002 and camt.053, which is how results and statements trace back)
 *   stl_items.net_minor          -> Amt/InstdAmt (major units, currency attribute)
 *   verified destination         -> CdtrAgt (institution, branch), Cdtr (account name),
 *                                   CdtrAcct (account number; MSISDN scheme for mobile wallets)
 *
 * The rendered document is validated against the official schema before it is returned:
 * an invalid instruction is never produced.
 */
@Component
public class Pain001PayoutAdapter implements PayoutFileAdapter {

    public static final String FORMAT = "ISO20022_PAIN_001_001_09";
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final MarketRegistry markets;
    private final Iso20022Schemas schemas;

    public Pain001PayoutAdapter(JdbcTemplate jdbc, MarketRegistry markets, Iso20022Schemas schemas) {
        this.jdbc = jdbc;
        this.markets = markets;
        this.schemas = schemas;
    }

    @Override
    public String format() {
        return FORMAT;
    }

    @Override
    public String extension() {
        return "xml";
    }

    @Override
    public String render(String batchRef, String currency, List<Map<String, Object>> items) {
        throw new UnsupportedOperationException("pain.001 needs the batch row");
    }

    @Override
    public String render(Map<String, Object> batch, List<Map<String, Object>> items) {
        String batchRef = (String) batch.get("batch_ref");
        String ccy = (String) batch.get("currency");
        MarketRegistry.Market market = markets.requireForCurrency(ccy);
        MarketRegistry.BankAccount debtor = markets.bankAccount(ccy);
        LocalDateTime created = batch.get("created_at") instanceof Timestamp t ? t.toLocalDateTime() : (LocalDateTime) batch.get("created_at");
        long total = items.stream().mapToLong(i -> ((Number) i.get("net_minor")).longValue()).sum();

        StringBuilder x = new StringBuilder(4096);
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        x.append("<Document xmlns=\"").append(Iso20022Schemas.namespace(Iso20022Schemas.PAIN_001)).append("\">\n<CstmrCdtTrfInitn>\n");
        x.append("<GrpHdr><MsgId>").append(Xml.esc(Xml.max(batchRef, 35))).append("</MsgId><CreDtTm>")
                .append(created.truncatedTo(ChronoUnit.SECONDS).format(DT)).append("</CreDtTm><NbOfTxs>").append(items.size())
                .append("</NbOfTxs><CtrlSum>").append(amount(total)).append("</CtrlSum><InitgPty><Nm>")
                .append(Xml.esc(Xml.max(debtor.name(), 140))).append("</Nm></InitgPty></GrpHdr>\n");
        x.append("<PmtInf><PmtInfId>").append(Xml.esc(Xml.max(batchRef, 35))).append("</PmtInfId><PmtMtd>TRF</PmtMtd><BtchBookg>false</BtchBookg><NbOfTxs>")
                .append(items.size()).append("</NbOfTxs><CtrlSum>").append(amount(total)).append("</CtrlSum>")
                .append("<ReqdExctnDt><Dt>").append(java.time.LocalDate.now().isBefore(created.toLocalDate()) ? created.toLocalDate() : java.time.LocalDate.now()).append("</Dt></ReqdExctnDt>")
                .append("<Dbtr><Nm>").append(Xml.esc(Xml.max(debtor.name(), 140))).append("</Nm></Dbtr>")
                .append("<DbtrAcct><Id><Othr><Id>").append(Xml.esc(Xml.max(debtor.number(), 34))).append("</Id></Othr></Id><Ccy>").append(ccy).append("</Ccy></DbtrAcct>")
                .append("<DbtrAgt><FinInstnId>").append(agent(market.bank().bic(), market.bank().name())).append("</FinInstnId></DbtrAgt>\n");
        for (Map<String, Object> i : items) {
            Map<String, Object> d = destination(i);
            String ref = Xml.esc(Xml.max((String) i.get("instruction_ref"), 35));
            boolean wallet = "MOBILE_WALLET".equals(d.get("type"));
            x.append("<CdtTrfTxInf><PmtId><InstrId>").append(ref).append("</InstrId><EndToEndId>").append(ref).append("</EndToEndId></PmtId>")
                    .append("<Amt><InstdAmt Ccy=\"").append(ccy).append("\">").append(amount(((Number) i.get("net_minor")).longValue())).append("</InstdAmt></Amt>")
                    .append("<CdtrAgt><FinInstnId><Nm>").append(Xml.esc(Xml.max((String) d.get("institution"), 140))).append("</Nm></FinInstnId>");
            if (d.get("branch") != null && !((String) d.get("branch")).isBlank()) {
                x.append("<BrnchId><Nm>").append(Xml.esc(Xml.max((String) d.get("branch"), 140))).append("</Nm></BrnchId>");
            }
            x.append("</CdtrAgt><Cdtr><Nm>").append(Xml.esc(Xml.max((String) d.get("account_name"), 140))).append("</Nm></Cdtr>")
                    .append("<CdtrAcct><Id><Othr><Id>").append(Xml.esc(Xml.max((String) d.get("account_number"), 34))).append("</Id>");
            if (wallet) x.append("<SchmeNm><Prtry>MSISDN</Prtry></SchmeNm>");
            x.append("</Othr></Id></CdtrAcct>")
                    .append("<RmtInf><Ustrd>").append(Xml.esc(Xml.max("Payport settlement " + batchRef, 140))).append("</Ustrd></RmtInf></CdtTrfTxInf>\n");
        }
        x.append("</PmtInf>\n</CstmrCdtTrfInitn>\n</Document>\n");
        String xml = x.toString();
        byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
        schemas.validate(Iso20022Schemas.PAIN_001, bytes);
        String sha = BankMessageService.sha256(bytes);
        if (jdbc.queryForList("SELECT id FROM iso_messages WHERE direction = 'OUT' AND message_type = ? AND sha256 = ?", Long.class,
                Iso20022Schemas.PAIN_001, sha).isEmpty()) {
            jdbc.update("INSERT INTO iso_messages (direction, message_type, msg_id, related_ref, sha256, schema_valid, content, created_by, created_at) "
                    + "VALUES ('OUT', ?, ?, ?, ?, TRUE, ?, 'SYSTEM:settlement', ?)", Iso20022Schemas.PAIN_001, Xml.max(batchRef, 35), batchRef, sha, xml,
                    Timestamp.valueOf(LocalDateTime.now()));
        }
        return xml;
    }

    private Map<String, Object> destination(Map<String, Object> item) {
        Object id = item.get("destination_id");
        if (id == null) {
            throw new IllegalStateException("Payout " + item.get("instruction_ref") + " has no verified settlement destination; ISO 20022 payouts need the account details");
        }
        return jdbc.queryForMap("SELECT type, account_name, account_number, institution, branch FROM merchant_settlement_destinations WHERE id = ?", id);
    }

    private static String agent(String bic, String name) {
        if (bic != null && bic.matches("[A-Z0-9]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?")) return "<BICFI>" + bic + "</BICFI>";
        return "<Nm>" + Xml.esc(Xml.max(name, 140)) + "</Nm>";
    }

    static String amount(long minor) {
        return BigDecimal.valueOf(minor, 2).toPlainString();
    }
}
