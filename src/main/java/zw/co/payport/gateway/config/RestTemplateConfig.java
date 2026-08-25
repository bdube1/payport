package zw.co.payport.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.List;

@Configuration
public class RestTemplateConfig {

    // List of domains that should use Tor proxy (ONLY EcoCash)
    private static final List<String> TOR_DOMAINS = List.of(
        "payonline.econet.co.zw",
        "api.ecocash.co.zw"
    );

    @Bean
    public RestTemplate restTemplate() {
        // Was entirely unbounded -- SimpleClientHttpRequestFactory with
        // no explicit timeouts falls back to the JVM default, which is
        // effectively infinite. A single slow or hanging provider call
        // could run indefinitely in the background even after a caller
        // (like WalletBalanceController's 25s aggregate ceiling) has
        // already given up waiting on it -- which is exactly why a
        // balance could show up in the logs minutes after the browser
        // was already told the request failed. Every provider call
        // across the whole gateway shares this one RestTemplate, so this
        // fixes it everywhere at once, not just wallet balances.
        // Was 8000/12000 -- and separately, WalletBalanceController was
        // giving up waiting on each provider after only 15s, LESS than
        // the ~20s these numbers already allowed a single call to take.
        // The outer wait was shorter than what the inner call was itself
        // allowed to take -- so a provider that was still working,
        // nowhere near hung, could get reported as failed while its real
        // response arrived seconds later (exactly why a balance could
        // show up in the logs after the browser was already told it
        // failed). This also hits EcoCash hardest of all, since Tor
        // routing is inherently slower than a direct connection by
        // design. Widened with real headroom now: this layer's worst
        // case is ~30s, and the caller-side wait (WalletBalanceController)
        // is set well above that, not just barely above it.
        SmartClientHttpRequestFactory factory = new SmartClientHttpRequestFactory();
        factory.setConnectTimeout(10000);
        factory.setReadTimeout(20000);
        return new RestTemplate(factory);
    }

    // Custom factory that chooses proxy based on the URL
    private static class SmartClientHttpRequestFactory extends SimpleClientHttpRequestFactory {
        
        @Override
        protected java.net.HttpURLConnection openConnection(java.net.URL url, java.net.Proxy proxy) throws java.io.IOException {
            String host = url.getHost();
            
            // Check if this URL needs Tor proxy
            boolean useTor = TOR_DOMAINS.stream().anyMatch(host::contains);
            
            if (useTor) {
                // Use Tor SOCKS proxy for EcoCash
                Proxy torProxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("localhost", 9050));
                return super.openConnection(url, torProxy);
            } else {
                // No proxy for everything else (OneMoney, NetOne, Econet, etc.)
                return super.openConnection(url, Proxy.NO_PROXY);
            }
        }
    }
}
