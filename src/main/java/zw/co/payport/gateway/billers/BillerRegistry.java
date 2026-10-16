package zw.co.payport.gateway.billers;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** All billers by provider code (the same code as their delivery adaptor and product types). */
@Component
public class BillerRegistry {

    private final Map<String, BillerAdaptor> billers = new LinkedHashMap<>();

    public BillerRegistry(List<BillerAdaptor> all) {
        for (BillerAdaptor b : all) {
            if (billers.put(b.profile().code().toUpperCase(Locale.ROOT), b) != null) {
                throw new IllegalStateException("two billers with code " + b.profile().code());
            }
        }
    }

    public Optional<BillerAdaptor> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(billers.get(code.toUpperCase(Locale.ROOT)));
    }

    public List<BillerAdaptor.Profile> catalogue() {
        return billers.values().stream().map(BillerAdaptor::profile).toList();
    }
}
