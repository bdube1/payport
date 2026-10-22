package zw.co.payport.gateway.service.adaptor.econet;

import org.apache.xmlrpc.XmlRpcException;
import org.apache.xmlrpc.client.XmlRpcClient;
import org.apache.xmlrpc.client.XmlRpcSunHttpTransport;
import org.apache.xmlrpc.client.XmlRpcSunHttpTransportFactory;
import org.apache.xmlrpc.client.XmlRpcTransport;
import org.apache.xmlrpc.common.XmlRpcStreamRequestConfig;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/** The library's own HTTP transport, with every response passed through {@link XmlRpcResponseGuard} before parsing (R76). */
public class GuardedXmlRpcTransportFactory extends XmlRpcSunHttpTransportFactory {

    private final XmlRpcClient client;

    public GuardedXmlRpcTransportFactory(XmlRpcClient client) {
        super(client);
        this.client = client;
    }

    @Override
    public XmlRpcTransport getTransport() {
        return new XmlRpcSunHttpTransport(client) {
            @Override
            protected Object readResponse(XmlRpcStreamRequestConfig config, InputStream stream) throws XmlRpcException {
                byte[] body;
                try {
                    body = XmlRpcResponseGuard.read(stream);
                } catch (XmlRpcResponseGuard.RefusedResponse e) {
                    throw new XmlRpcException(e.getMessage());
                } catch (java.io.IOException e) {
                    throw new XmlRpcException("Failed to read XML-RPC response: " + e.getMessage(), e);
                }
                return super.readResponse(config, new ByteArrayInputStream(body));
            }
        };
    }
}
