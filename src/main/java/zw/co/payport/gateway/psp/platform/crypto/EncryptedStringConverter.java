package zw.co.payport.gateway.psp.platform.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter for encrypted string columns. Hibernate instantiates
 * converters itself, so the crypto instance is installed once at startup
 * by {@link CryptoConfig} rather than injected.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    private static volatile FieldCrypto crypto;

    static void install(FieldCrypto instance) {
        crypto = instance;
    }

    private static FieldCrypto crypto() {
        FieldCrypto c = crypto;
        if (c == null) throw new IllegalStateException("FieldCrypto not initialised");
        return c;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : crypto().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : crypto().decrypt(dbData);
    }
}
