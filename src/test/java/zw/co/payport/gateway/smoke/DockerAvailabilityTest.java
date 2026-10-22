package zw.co.payport.gateway.smoke;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * In CI, Docker must be usable: otherwise the Testcontainers suites (application smoke test,
 * Flyway migration test) skip silently. This test turns that into a failure with the real reason.
 */
class DockerAvailabilityTest {

    @Test
    void dockerIsUsableInCi() {
        Assumptions.assumeTrue(System.getenv("CI") != null, "only enforced in CI");
        try {
            DockerClientFactory.instance().client().pingCmd().exec();
        } catch (Throwable t) {
            StringBuilder sb = new StringBuilder();
            for (Throwable c = t; c != null; c = c.getCause()) sb.append(c.getClass().getName()).append(": ").append(c.getMessage()).append(" | ");
            fail("Testcontainers cannot use Docker: " + sb);
        }
    }
}
