package zw.co.payport.gateway.psp.platform.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import zw.co.payport.gateway.psp.ledger.domain.ChainHasher;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Seals append-only tables into a hash chain every minute and verifies the
 * whole chain on demand / nightly. See {@link ChainHasher} for the scheme.
 *
 * Rows are only sealed once they are two minutes old, so a slow transaction
 * that commits a lower id after a higher one is still included in order.
 */
@Service
public class ChainSealService {

    private static final Logger log = LoggerFactory.getLogger(ChainSealService.class);
    private static final int BATCH = 5_000;

    public record ChainReport(String chain, int sealsChecked, long rowsChecked, boolean intact,
                              List<String> problems, String headHash) {}

    private final ChainSealRepository seals;
    private final Map<String, ChainSource> sources;

    public ChainSealService(ChainSealRepository seals, List<ChainSource> sources) {
        this.seals = seals;
        this.sources = sources.stream().collect(Collectors.toMap(ChainSource::chainName, Function.identity()));
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void sealAll() {
        for (String chain : sources.keySet()) {
            try {
                sealChain(chain);
            } catch (Exception e) {
                log.error("[SEAL] Sealing {} failed: {}", chain, e.getMessage(), e);
            }
        }
    }

    @Transactional
    public ChainSeal sealChain(String chain) {
        ChainSource src = sources.get(chain);
        ChainSeal last = seals.findTopByChainNameOrderByToIdDesc(chain).orElse(null);
        long afterId = last != null ? last.getToId() : 0L;
        String prev = last != null ? last.getSealHash() : ChainHasher.GENESIS;

        List<ChainSource.Row> rows = src.sealable(afterId, LocalDateTime.now().minusMinutes(2), BATCH);
        if (rows.isEmpty()) return null;

        List<String> hashes = rows.stream().map(ChainSource.Row::storedHash).toList();
        long from = rows.get(0).id();
        long to = rows.get(rows.size() - 1).id();

        ChainSeal seal = new ChainSeal();
        seal.setChainName(chain);
        seal.setFromId(from);
        seal.setToId(to);
        seal.setRowCount(rows.size());
        seal.setPrevSealHash(prev);
        seal.setSealHash(ChainHasher.sealHash(prev, from, to, hashes));
        seals.save(seal);
        return seal;
    }

    /** Full verification: every row's content hash, every seal, and the links between seals. */
    @Transactional(readOnly = true)
    public ChainReport verify(String chain) {
        ChainSource src = sources.get(chain);
        if (src == null) throw new IllegalArgumentException("Unknown chain " + chain);
        List<String> problems = new ArrayList<>();
        String prev = ChainHasher.GENESIS;
        long rowsChecked = 0;
        long lastTo = 0;
        List<ChainSeal> all = seals.findByChainNameOrderByToIdAsc(chain);
        for (ChainSeal s : all) {
            if (!s.getPrevSealHash().equals(prev)) {
                problems.add("Seal " + s.getId() + " does not link to the previous seal");
            }
            if (s.getFromId() <= lastTo) {
                problems.add("Seal " + s.getId() + " overlaps the previous seal");
            }
            List<ChainSource.Row> rows = src.range(lastTo + 1, s.getToId());
            rowsChecked += rows.size();
            List<ChainSource.Row> inSeal = rows.stream().filter(r -> r.id() >= s.getFromId()).toList();
            if (rows.size() != inSeal.size()) {
                problems.add((rows.size() - inSeal.size()) + " row(s) appeared before seal " + s.getId() + " after it was sealed (late commit or insertion)");
            }
            for (ChainSource.Row r : inSeal) {
                if (!r.storedHash().equals(r.recomputedHash())) {
                    problems.add("Row " + r.id() + " content does not match its hash (modified)");
                }
            }
            String recomputed = ChainHasher.sealHash(s.getPrevSealHash(), s.getFromId(), s.getToId(),
                    inSeal.stream().map(ChainSource.Row::storedHash).toList());
            if (inSeal.size() != s.getRowCount() || !recomputed.equals(s.getSealHash())) {
                problems.add("Seal " + s.getId() + " (rows " + s.getFromId() + "-" + s.getToId() + ") no longer matches: rows deleted, added or altered");
            }
            prev = s.getSealHash();
            lastTo = s.getToId();
        }
        if (!problems.isEmpty()) {
            log.error("[SEAL] {} chain verification FAILED: {}", chain, problems);
        }
        return new ChainReport(chain, all.size(), rowsChecked, problems.isEmpty(), problems, prev);
    }

    public List<String> chains() {
        return new ArrayList<>(sources.keySet());
    }
}
