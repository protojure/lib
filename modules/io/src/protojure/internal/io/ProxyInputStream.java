package protojure.internal.io;

import java.io.IOException;

// adds a Proxy between java.io.InputStream and a core.async-based backend, by way of a reified AsyncInputStream
public class ProxyInputStream extends java.io.InputStream {

    public ProxyInputStream(AsyncInputStream backend) {
        m_backend = backend;
    }

    private final AsyncInputStream m_backend;

    @Override
    public int available () throws IOException {
        return m_backend.available();
    }

    @Override
    public int read() throws IOException {
        return m_backend.read_int();
    }

    @Override
    public int read(byte[] bytes) throws IOException {
        return m_backend.read_bytes(bytes);
    }

    @Override
    public int read(byte[] bytes, int offset, int len) throws IOException {
        return m_backend.read_offset(bytes, offset, len);
    }
}
