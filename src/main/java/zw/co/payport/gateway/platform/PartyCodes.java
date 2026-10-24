package zw.co.payport.gateway.platform;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Six-digit codes customers type to pay a merchant or cash out at an agent. One allocation table for
 * agents and merchants together (party_codes, code is the primary key), so a code is never given twice,
 * not even to an agent and a merchant. Codes are random (not sequential) and never reused.
 * Agents and merchants created before codes existed get one at start-up.
 */
@Slf4j
@Service
public class PartyCodes implements ApplicationRunner {

    public static final String AGENT = "AGENT";
    public static final String MERCHANT = "MERCHANT";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public PartyCodes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The party's code, allocating one the first time. */
    public String allocate(String partyType, long partyId) {
        String type = partyType.toUpperCase(Locale.ROOT);
        Optional<String> existing = codeOf(type, partyId);
        if (existing.isPresent()) return existing.get();
        for (int attempt = 0; attempt < 50; attempt++) {
            String code = String.valueOf(100_000 + RANDOM.nextInt(900_000));
            try {
                jdbc.update("INSERT INTO party_codes (code, party_type, party_id, created_at) VALUES (?, ?, ?, ?)",
                        code, type, partyId, Timestamp.valueOf(LocalDateTime.now()));
            } catch (DuplicateKeyException e) {
                Optional<String> raced = codeOf(type, partyId);
                if (raced.isPresent()) return raced.get();
                continue;   // the code belongs to someone else: draw again
            }
            if (AGENT.equals(type)) jdbc.update("UPDATE agents SET code = ? WHERE id = ?", code, partyId);
            else jdbc.update("UPDATE merchants SET merchant_code = ? WHERE id = ?", code, partyId);
            return code;
        }
        throw new IllegalStateException("Could not allocate a code, please try again");
    }

    public Optional<String> codeOf(String partyType, long partyId) {
        return jdbc.queryForList("SELECT code FROM party_codes WHERE party_type = ? AND party_id = ?", String.class,
                partyType.toUpperCase(Locale.ROOT), partyId).stream().findFirst();
    }

    /** The party behind a code, if the code belongs to that kind of party. */
    public Optional<Long> resolve(String partyType, String code) {
        String c = code == null ? "" : code.replaceAll("[^0-9]", "");
        if (c.length() != 6) return Optional.empty();
        return jdbc.queryForList("SELECT party_id FROM party_codes WHERE code = ? AND party_type = ?", Long.class,
                c, partyType.toUpperCase(Locale.ROOT)).stream().findFirst();
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<Long> agents = jdbc.queryForList("SELECT id FROM agents WHERE code IS NULL", Long.class);
            for (Long id : agents) allocate(AGENT, id);
            List<Long> merchants = jdbc.queryForList("SELECT id FROM merchants WHERE merchant_code IS NULL", Long.class);
            for (Long id : merchants) allocate(MERCHANT, id);
            if (!agents.isEmpty() || !merchants.isEmpty()) log.info("[CODES] allocated codes to {} agent(s) and {} merchant(s)", agents.size(), merchants.size());
        } catch (Exception e) {
            log.warn("[CODES] start-up allocation skipped: {}", e.getMessage());
        }
    }
}
