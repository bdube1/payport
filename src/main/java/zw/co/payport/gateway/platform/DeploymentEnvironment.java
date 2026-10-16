package zw.co.payport.gateway.platform;

import java.util.Locale;
import java.util.Set;

/**
 * Which kind of deployment this is (payport.environment / PAYPORT_ENV). Anything that is not
 * explicitly a development or test deployment is treated as production, so a missing setting
 * fails safe: production rules apply.
 */
public final class DeploymentEnvironment {

    private static final Set<String> NON_PRODUCTION = Set.of("local", "dev", "development", "test", "ci", "sandbox", "staging");

    private DeploymentEnvironment() {}

    public static boolean isProduction(String value) {
        return value == null || value.isBlank() || !NON_PRODUCTION.contains(value.trim().toLowerCase(Locale.ROOT));
    }
}
