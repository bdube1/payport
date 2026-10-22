package zw.co.payport.gateway.ledger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import zw.co.payport.gateway.billing.TaxRateService;
import zw.co.payport.gateway.ledger.shadow.ShadowComparison;
import zw.co.payport.gateway.ledger.shadow.ShadowLedgerSweeper;
import zw.co.payport.gateway.platform.JobRunner;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Locale;

/** Wires the Phase 1 ledger, shadow posting and platform services (plain JDBC on the app's pool). */
@Configuration
public class LedgerConfig {

    @Bean
    public LedgerClient ledgerClient(DataSource dataSource) {
        return new LedgerClient(dataSource);
    }

    @Bean
    public JobRunner jobRunner(DataSource dataSource) {
        return new JobRunner(dataSource);
    }

    @Bean
    public TaxRateService taxRateService(DataSource dataSource) {
        return new TaxRateService(dataSource);
    }

    @Bean
    public ShadowLedgerSweeper shadowLedgerSweeper(DataSource dataSource, LedgerClient ledgerClient, Environment env) {
        return new ShadowLedgerSweeper(dataSource, ledgerClient, entity(env),
                ccy -> env.getProperty("payport.ledger.settlement-bank." + ccy.toUpperCase(Locale.ROOT), "SETTLEMENT_" + ccy.toUpperCase(Locale.ROOT)),
                vendor -> Integer.valueOf(env.getProperty("payport.ledger.vendor-commission-bps." + vendor, "0")),
                Duration.ofMinutes(Long.parseLong(env.getProperty("payport.ledger.shadow.overlap-minutes", "10"))),
                Integer.parseInt(env.getProperty("payport.ledger.shadow.page-size", "500")));
    }

    @Bean
    public ShadowComparison shadowComparison(DataSource dataSource, Environment env) {
        return new ShadowComparison(dataSource, entity(env));
    }

    static String entity(Environment env) {
        return env.getProperty("payport.ledger.entity", "PAYPORT_ZW");
    }
}
