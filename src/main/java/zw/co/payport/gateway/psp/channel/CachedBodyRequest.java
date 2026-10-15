package zw.co.payport.gateway.psp.channel;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Reads the body once (for signature checks) and lets the controller read it again. Bodies over 1 MB are rejected. */
public class CachedBodyRequest extends HttpServletRequestWrapper {

    public static final int MAX_BYTES = 1_048_576;
    private final byte[] body;

    public CachedBodyRequest(HttpServletRequest request) throws IOException {
        super(request);
        byte[] b = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (b.length > MAX_BYTES) throw new IOException("Request body too large");
        this.body = b;
    }

    public byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream in = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public boolean isFinished() { return in.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener l) { throw new UnsupportedOperationException(); }
            @Override public int read() { return in.read(); }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
    }
}
