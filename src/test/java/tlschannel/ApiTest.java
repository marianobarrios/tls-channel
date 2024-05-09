package tlschannel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import tlschannel.impl.ByteBufferSet;
import tlschannel.impl.TlsChannelImpl;

@TestInstance(Lifecycle.PER_CLASS)
public class ApiTest {

    private static final int arraySize = 1024;

    private final ReadableByteChannel readChannel = Channels.newChannel(new ByteArrayInputStream(new byte[arraySize]));
    private final WritableByteChannel writeChannel = Channels.newChannel(new ByteArrayOutputStream(arraySize));

    private TlsChannelImpl newSocket() {
        try {
            SSLEngine sslEngine = SSLContext.getDefault().createSSLEngine();
            return new TlsChannelImpl(
                    readChannel,
                    writeChannel,
                    sslEngine,
                    Optional.empty(),
                    session -> {},
                    true,
                    new TrackingAllocator(new HeapBufferAllocator()),
                    new TrackingAllocator(new HeapBufferAllocator()),
                    true /* releaseBuffers */,
                    false /* waitForCloseConfirmation */);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    public void testReadIntoReadOnlyBuffer() throws IOException {
        try (TlsChannelImpl socket = newSocket()) {
            Assertions.assertThrows(IllegalArgumentException.class, () -> {
                socket.read(new ByteBufferSet(ByteBuffer.allocate(1).asReadOnlyBuffer()));
            });
        }
    }

    @Test
    public void testReadIntoBufferWithoutCapacity() throws IOException {
        try (TlsChannelImpl socket = newSocket()) {
            Assertions.assertEquals(
                    0,
                    socket.read(new ByteBufferSet(ByteBuffer.allocate(0))),
                    "read must return zero when the buffer was empty");
        }
    }
}
