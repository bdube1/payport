package zw.co.payport.gateway.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The backend's API contract: every route (method, path) of the running application must equal
 * docs/api/api-contract.json, which the frontend repositories copy and check every call against
 * (scripts/check-api-contract.mjs, run by their CI). A route added, removed or renamed without
 * updating the contract fails here; the full export (with access rules) goes to target/api-contract.json.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class ApiContractExportTest {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.0");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("DB_URL", MYSQL::getJdbcUrl);
        r.add("DB_USERNAME", MYSQL::getUsername);
        r.add("DB_PASSWORD", MYSQL::getPassword);
        r.add("JWT_SECRET", () -> "contract-test-secret-contract-test-secret-0123456789abcdef");
        r.add("payport.payments.resolver.enabled", () -> "false");
        r.add("payport.payments.watchdog.enabled", () -> "false");
        r.add("payport.fulfilment.worker.enabled", () -> "false");
        r.add("payport.screening.un.enabled", () -> "false");
    }

    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    @Test
    void exportContract() throws Exception {
        List<Map<String, Object>> routes = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e : mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = e.getKey();
            HandlerMethod h = e.getValue();
            PreAuthorize pa = h.getMethodAnnotation(PreAuthorize.class);
            if (pa == null) pa = h.getBeanType().getAnnotation(PreAuthorize.class);
            TreeSet<String> methods = new TreeSet<>();
            info.getMethodsCondition().getMethods().forEach(m -> methods.add(m.name()));
            if (methods.isEmpty()) methods.add("ANY");
            for (String path : info.getPatternValues()) {
                for (String m : methods) {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("method", m);
                    r.put("path", path);
                    r.put("access", pa == null ? null : pa.value());
                    r.put("handler", h.getBeanType().getSimpleName() + "." + h.getMethod().getName());
                    routes.add(r);
                }
            }
        }
        routes.sort(Comparator.comparing((Map<String, Object> r) -> (String) r.get("path")).thenComparing(r -> (String) r.get("method")));
        assertTrue(routes.size() > 100, "routes found: " + routes.size());
        Files.createDirectories(Path.of("target"));
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(Path.of("target/api-contract.json").toFile(),
                Map.of("generatedBy", "ApiContractExportTest", "routes", routes));

        TreeSet<String> live = new TreeSet<>();
        for (Map<String, Object> r : routes) live.add(r.get("method") + " " + r.get("path"));
        TreeSet<String> committed = new TreeSet<>();
        for (var r : new ObjectMapper().readTree(Path.of("docs/api/api-contract.json").toFile()).path("routes")) {
            committed.add(r.path("method").asText() + " " + r.path("path").asText());
        }
        TreeSet<String> missing = new TreeSet<>(live), extra = new TreeSet<>(committed);
        missing.removeAll(committed);
        extra.removeAll(live);
        assertTrue(missing.isEmpty() && extra.isEmpty(), "docs/api/api-contract.json is out of date. Add: " + missing + " Remove: " + extra);
    }
}
