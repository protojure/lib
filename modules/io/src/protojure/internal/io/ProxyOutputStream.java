package protojure.internal.io;

import java.io.IOException;

// adds a Proxy between java.io.OutputStream and a core.async-based backend, by way of a reified AsyncOutputStream
public class ProxyOutputStream extends java.io.OutputStream {

    public ProxyOutputStream(AsyncOutputStream backend) {
        m_backend = backend;
    }

    private final AsyncOutputStream m_backend;

    @Override
    public void flush() throws IOException {
        m_backend.flush();
    }

    @Override
    public void close() throws IOException {
        m_backend.close();
    }

    @Override
    public void write(int b) throws IOException {
        m_backend.write_int(b);
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        m_backend.write_bytes(bytes);
    }

    @Override
    public void write(byte[] bytes, int offset, int len) throws IOException {
        m_backend.write_offset(bytes, offset, len);
    }
}
