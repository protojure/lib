package protojure.internal.io;

import java.io.IOException;

public interface AsyncOutputStream {
    public void flush() throws IOException;
    public void close() throws IOException;
    public void write_int(int b) throws IOException;
    public void write_bytes(byte[] b) throws IOException;
    public void write_offset(byte[] b, int offset, int len) throws IOException;
}
