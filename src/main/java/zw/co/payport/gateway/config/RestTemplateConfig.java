package zw.co.payport.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.net.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The RestTemplate every provider call uses. EcoCash only accepts Zimbabwean addresses, so its hosts go through the
 * egress proxy (ECOCASH_EGRESS_*: the local Tor SOCKS port by default, or a SOCKS/HTTP proxy such as an SSH tunnel to
 * a Zimbabwean server); everything else goes direct. See EgressRequestFactory for the safe retries.
 *
 * Timeouts: 10s to connect, 20s to read (every provider call shares this, so a hanging provider can never run on in
 * the background after the caller has given up).
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public EgressRequestFactory egressRequestFactory(
            @Value("${payport.ecocash.egress.enabled:true}") boolean enabled,
            @Value("${payport.ecocash.egress.type:SOCKS}") String type,
            @Value("${payport.ecocash.egress.host:127.0.0.1}") String host,
            @Value("${payport.ecocash.egress.port:9050}") int port,
            @Value("${payport.ecocash.egress.hosts:payonline.econet.co.zw,api.ecocash.co.zw}") String hosts) {
        Proxy.Type t = "HTTP".equalsIgnoreCase(type.trim()) ? Proxy.Type.HTTP : Proxy.Type.SOCKS;
        List<String> list = Arrays.stream(hosts.split(",")).map(x -> x.trim().toLowerCase(Locale.ROOT)).filter(x -> !x.isEmpty()).toList();
        return new EgressRequestFactory(enabled, t, host.trim(), port, list, 10000, 20000, 1500);
    }

    @Bean
    public RestTemplate restTemplate(EgressRequestFactory factory) {
        return new RestTemplate(factory);
    }
}
