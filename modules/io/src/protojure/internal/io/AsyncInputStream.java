package protojure.internal.io;

import java.io.IOException;

public interface AsyncInputStream {
    public int available () throws IOException;
    public int read_int() throws IOException;
    public int read_bytes(byte[] b) throws IOException;
    public int read_offset(byte[] b, int offset, int len) throws IOException;
}
