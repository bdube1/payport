package zw.co.payport.gateway.psp.platform.crypto;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CryptoConfig {

    @Bean
    public FieldCrypto fieldCrypto(KeyProvider keyProvider) {
        FieldCrypto crypto = new FieldCrypto(keyProvider);
        EncryptedStringConverter.install(crypto);
        return crypto;
    }
}
