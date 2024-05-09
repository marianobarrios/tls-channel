package tlschannel.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.Assertions;
import tlschannel.TlsChannel;

public class InteroperabilityUtils {

    public interface Reader {
        int read(byte[] array, int offset, int length) throws IOException;

        void close() throws IOException;
    }

    public interface Writer {
        void renegotiate() throws IOException;

        void write(byte[] array, int offset, int length) throws IOException;

        void close() throws IOException;
    }

    public static class SocketReader implements Reader {

        private final Socket socket;
        private final InputStream is;

        public SocketReader(Socket socket) throws IOException {
            this.socket = socket;
            this.is = socket.getInputStream();
        }

        @Override
        public int read(byte[] array, int offset, int length) throws IOException {
            return is.read(array, offset, length);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    public static class ByteChannelReader implements Reader {

        private final ByteChannel socket;

        public ByteChannelReader(ByteChannel socket) {
            this.socket = socket;
        }

        @Override
        public int read(byte[] array, int offset, int length) throws IOException {
            return socket.read(ByteBuffer.wrap(array, offset, length));
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    public static class SSLSocketWriter implements Writer {

        private final SSLSocket socket;
        private final OutputStream os;

        public SSLSocketWriter(SSLSocket socket) throws IOException {
            this.socket = socket;
            this.os = socket.getOutputStream();
        }

        @Override
        public void write(byte[] array, int offset, int length) throws IOException {
            os.write(array, offset, length);
        }

        @Override
        public void renegotiate() throws IOException {
            socket.startHandshake();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    public static class TlsChannelWriter implements Writer {

        private final TlsChannel socket;

        public TlsChannelWriter(TlsChannel socket) {
            this.socket = socket;
        }

        @Override
        public void write(byte[] array, int offset, int length) throws IOException {
            ByteBuffer buffer = ByteBuffer.wrap(array, offset, length);
            while (buffer.remaining() > 0) {
                int c = socket.write(buffer);
                Assertions.assertNotEquals(0, c, "blocking write cannot return 0");
            }
        }

        @Override
        public void renegotiate() throws IOException {
            socket.renegotiate();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
