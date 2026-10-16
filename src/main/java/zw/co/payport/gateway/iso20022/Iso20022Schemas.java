package zw.co.payport.gateway.iso20022;

import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validation against the official ISO 20022 message schemas (iso20022.org), loaded from
 * classpath iso20022/xsd/&lt;message&gt;.xsd. The schemas are fetched and their SHA-256 checked
 * by scripts/iso20022/fetch-schemas.sh (pinned in scripts/iso20022/schemas.sha256).
 *
 * Fails closed: a message is never produced or accepted when its schema is not installed.
 * Parsing refuses DTDs and external entities, and validation may not fetch anything.
 */
@Component
public class Iso20022Schemas {

    public static final String CAMT_053 = "camt.053.001.08";
    public static final String PAIN_001 = "pain.001.001.09";
    public static final String PAIN_002 = "pain.002.001.10";

    public static class InvalidMessageException extends RuntimeException {
        public InvalidMessageException(String message) { super(message); }
    }

    private final Map<String, Schema> cache = new ConcurrentHashMap<>();

    public static String namespace(String message) {
        return "urn:iso:std:iso:20022:tech:xsd:" + message;
    }

    public boolean installed(String message) {
        return getClass().getResource("/iso20022/xsd/" + message + ".xsd") != null;
    }

    Schema schema(String message) {
        return cache.computeIfAbsent(message, m -> {
            InputStream in = getClass().getResourceAsStream("/iso20022/xsd/" + m + ".xsd");
            if (in == null) throw new InvalidMessageException("The official ISO 20022 schema for " + m + " is not installed; the message cannot be validated");
            try (in) {
                SchemaFactory f = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
                f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
                f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                f.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                return f.newSchema(new StreamSource(in));
            } catch (Exception e) {
                throw new IllegalStateException("ISO 20022 schema " + m + " could not be loaded: " + e.getMessage(), e);
            }
        });
    }

    /** Parses securely and validates against the official schema; returns the DOM. */
    public Document validate(String message, byte[] xml) {
        Document doc = parse(xml);
        String ns = doc.getDocumentElement().getNamespaceURI();
        if (!namespace(message).equals(ns)) {
            throw new InvalidMessageException("Expected a " + message + " document (namespace " + namespace(message) + "), got " + ns);
        }
        try {
            Validator v = schema(message).newValidator();
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            v.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            v.validate(new DOMSource(doc));
        } catch (SAXException e) {
            throw new InvalidMessageException(message + " does not conform to the ISO 20022 schema: " + e.getMessage());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return doc;
    }

    public static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        } catch (SAXException e) {
            throw new InvalidMessageException("Not a well-formed XML document: " + e.getMessage());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
