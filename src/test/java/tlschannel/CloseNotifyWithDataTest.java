package tlschannel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.SplittableRandom;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import tlschannel.helpers.SslContextFactory;
import tlschannel.helpers.TestUtil;
import tlschannel.util.Util;

/**
 * Tests the read semantics when application data and a close_notify alert are delivered in the
 * same network segment (as separate TLS records), as happens when a server responds and
 * immediately closes the connection. The data and the close should be reported in a single
 * read() call, so callers that need the end of stream to delimit a response (e.g. HTTP
 * responses without Content-Length) see them together.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class CloseNotifyWithDataTest {

    private final List<String> protocols = Arrays.asList("TLSv1.2", "TLSv1.3");

    @TestFactory
    public Collection<DynamicTest> testDataAndCloseNotifyFromOneSegmentInOneRead() {
        return forAllProtocols("testDataAndCloseNotifyFromOneSegmentInOneRead", protocol -> {
            Channels channels = handshakedChannels(protocol);
            byte[] payload = randomBytes(300);
            channels.server.write(ByteBuffer.wrap(payload));
            channels.server.close();

            // all server bytes (data record + close_notify record) are now buffered on the wire,
            // simulating delivery in a single TCP segment; destination is larger than the payload
            // so buffer size is not under test here
            ByteBuffer dest = ByteBuffer.allocate(1024);
            assertEquals(payload.length, channels.client.read(dest));
            assertEquals(payload.length, dest.position());
            assertTrue(
                    channels.client.shutdownReceived(),
                    "close_notify buffered behind the data should be detected in the same read");

            dest.flip();
            byte[] received = new byte[dest.remaining()];
            dest.get(received);
            assertArrayEquals(payload, received);

            assertEquals(-1, channels.client.read(ByteBuffer.allocate(1)));

            // the shutdown can be completed from the reader side without further reads
            assertTrue(channels.client.shutdown());
            channels.client.close();
        });
    }

    @TestFactory
    public Collection<DynamicTest> testCloseNotifyInSeparateSegmentIsReportedOnNextRead() {
        return forAllProtocols("testCloseNotifyInSeparateSegmentIsReportedOnNextRead", protocol -> {
            Channels channels = handshakedChannels(protocol);
            byte[] payload = randomBytes(300);
            channels.server.write(ByteBuffer.wrap(payload));

            // only the data is buffered; the close arrives later, in its own segment;
            // destination is larger than the payload so buffer size is not under test here
            ByteBuffer dest = ByteBuffer.allocate(1024);
            assertEquals(payload.length, channels.client.read(dest));
            assertFalse(channels.client.shutdownReceived());

            channels.server.close();
            assertEquals(-1, channels.client.read(dest));
            assertTrue(channels.client.shutdownReceived());
            channels.client.close();
        });
    }

    @TestFactory
    public Collection<DynamicTest> testResponseSpanningMultipleRecordsCountsAllBytes() {
        return forAllProtocols("testResponseSpanningMultipleRecordsCountsAllBytes", protocol -> {
            Channels channels = handshakedChannels(protocol);
            // large enough to require several TLS records (max ~16KB plaintext each)
            byte[] payload = randomBytes(50_000);
            channels.server.write(ByteBuffer.wrap(payload));
            channels.server.close();

            // destination is larger than any single TLS record so it never fills up mid-read;
            // this test verifies byte counts are correct across records, not partial-delivery
            assertReadsDeliverExactly(channels.client, payload, ByteBuffer.allocate(64 * 1024));
        });
    }

    @TestFactory
    public Collection<DynamicTest> testDestinationFillingUpMidResponse() {
        return forAllProtocols("testDestinationFillingUpMidResponse", protocol -> {
            Channels channels = handshakedChannels(protocol);
            byte[] payload = randomBytes(50_000);
            channels.server.write(ByteBuffer.wrap(payload));
            channels.server.close();

            // the destination fits the first TLS record but fills up while further buffered
            // records remain, so a partial count must be returned
            assertReadsDeliverExactly(channels.client, payload, ByteBuffer.allocate(20_000));
        });
    }

    @TestFactory
    public Collection<DynamicTest> testDestinationSmallerThanTlsRecord() {
        return forAllProtocols("testDestinationSmallerThanTlsRecord", protocol -> {
            Channels channels = handshakedChannels(protocol);
            byte[] payload = randomBytes(50_000);
            channels.server.write(ByteBuffer.wrap(payload));
            channels.server.close();

            // a destination smaller than a single TLS record forces overflow into the channel's
            // internal plaintext buffer
            assertReadsDeliverExactly(channels.client, payload, ByteBuffer.allocate(1000));
        });
    }

    @TestFactory
    public Collection<DynamicTest> testCorruptRecordAfterDataStillDeliversData() {
        return forAllProtocols("testCorruptRecordAfterDataStillDeliversData", protocol -> {
            // JDK 8's SSLEngine reports malformed records inconsistently across builds (older builds
            // throw IllegalArgumentException instead of SSLException); the modern semantics this test
            // asserts are only guaranteed on Java 9+
            assumeTrue(Util.getJavaMajorVersion() >= 9, "requires Java 9+ SSLEngine error reporting");
            Channels channels = handshakedChannels(protocol);
            byte[] payload = randomBytes(300);
            channels.server.write(ByteBuffer.wrap(payload));

            // append a corrupt TLS record in the same segment as the data: a complete
            // application-data record whose ciphertext cannot be decrypted
            byte[] garbage = new byte[37];
            TestUtil.nextBytes(new SplittableRandom(13), garbage);
            ByteBuffer corruptRecord = ByteBuffer.allocate(5 + garbage.length);
            corruptRecord.put((byte) 23); // content type: application data
            corruptRecord.put((byte) 3).put((byte) 3); // legacy version TLS 1.2
            corruptRecord.putShort((short) garbage.length);
            corruptRecord.put(garbage);
            corruptRecord.flip();
            channels.serverToClient.add(corruptRecord);

            // the data preceding the corrupt record must still be delivered;
            // destination is larger than the 300-byte payload so buffer size is not under test here
            ByteBuffer dest = ByteBuffer.allocate(1024);
            assertEquals(payload.length, channels.client.read(dest));
            assertEquals(payload.length, dest.position());

            // ... and the failure surfaces on the next read
            assertThrows(IOException.class, () -> channels.client.read(dest));
        });
    }

    private interface ProtocolTest {
        void run(String protocol) throws IOException;
    }

    private Collection<DynamicTest> forAllProtocols(String testName, ProtocolTest test) {
        List<DynamicTest> tests = new ArrayList<>();
        for (String protocol : protocols) {
            tests.add(DynamicTest.dynamicTest(
                    String.format("%s() - protocol: %s", testName, protocol), () -> test.run(protocol)));
        }
        return tests;
    }

    private static void assertReadsDeliverExactly(TlsChannel client, byte[] payload, ByteBuffer dest)
            throws IOException {
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        while (true) {
            int positionBeforeRead = dest.position();
            int bytesRead = client.read(dest);
            if (bytesRead == -1) {
                break;
            }
            assertEquals(
                    positionBeforeRead + bytesRead,
                    dest.position(),
                    "read() must report exactly the number of bytes transferred into the destination");
            dest.flip();
            byte[] chunk = new byte[dest.remaining()];
            dest.get(chunk);
            received.write(chunk, 0, chunk.length);
            dest.clear();
        }
        assertArrayEquals(payload, received.toByteArray());
        assertTrue(client.shutdownReceived());
        client.close();
    }

    private static final class Channels {
        final TlsChannel client;
        final TlsChannel server;
        final Wire serverToClient;

        Channels(TlsChannel client, TlsChannel server, Wire serverToClient) {
            this.client = client;
            this.server = server;
            this.serverToClient = serverToClient;
        }
    }

    private static Channels handshakedChannels(String protocol) throws IOException {
        assumeProtocolSupported(protocol);
        SSLContext sslContext = new SslContextFactory(protocol).defaultContext();
        Wire clientToServer = new Wire();
        Wire serverToClient = new Wire();

        SSLEngine clientEngine = sslContext.createSSLEngine();
        clientEngine.setUseClientMode(true);
        clientEngine.setEnabledProtocols(new String[] {protocol});
        TlsChannel client = ClientTlsChannel.newBuilder(new WireChannel(serverToClient, clientToServer), clientEngine)
                .build();
        TlsChannel server = ServerTlsChannel.newBuilder(new WireChannel(clientToServer, serverToClient), sslContext)
                .build();

        pumpHandshake(client, server);
        return new Channels(client, server, serverToClient);
    }

    private static void assumeProtocolSupported(String protocol) {
        try {
            // older JDK 8 builds do not support TLSv1.3 at all
            assumeTrue(
                    Arrays.asList(SSLContext.getDefault().getSupportedSSLParameters().getProtocols())
                            .contains(protocol),
                    protocol + " is not supported by this JVM");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static void pumpHandshake(TlsChannel client, TlsChannel server) throws IOException {
        boolean clientDone = false;
        boolean serverDone = false;
        int rounds = 0;
        while (!(clientDone && serverDone)) {
            assertTrue(rounds++ < 100, "handshake did not complete");
            // only NeedsReadException can occur: WireChannel accepts all writes, and tasks run
            // inline by default, so NeedsWriteException/NeedsTaskException are impossible
            if (!clientDone) {
                try {
                    client.handshake();
                    clientDone = true;
                } catch (NeedsReadException e) {
                    // waiting for server bytes
                }
            }
            if (!serverDone) {
                try {
                    server.handshake();
                    serverDone = true;
                } catch (NeedsReadException e) {
                    // waiting for client bytes
                }
            }
        }
    }

    private static byte[] randomBytes(int size) {
        byte[] bytes = new byte[size];
        TestUtil.nextBytes(new SplittableRandom(42), bytes);
        return bytes;
    }

    /**
     * A one-directional, in-memory byte stream. Reads return 0 when no bytes are buffered
     * (non-blocking semantics) and -1 once closed and drained. Because all written bytes are
     * available at once, it deterministically simulates records arriving in a single segment.
     */
    private static final class Wire {
        private final Deque<ByteBuffer> chunks = new ArrayDeque<>();
        private volatile boolean closed;

        void add(ByteBuffer src) {
            byte[] copy = new byte[src.remaining()];
            src.get(copy);
            chunks.add(ByteBuffer.wrap(copy));
        }

        int drainTo(ByteBuffer dst) {
            if (chunks.isEmpty()) {
                return closed ? -1 : 0;
            }
            int transferred = 0;
            while (dst.hasRemaining() && !chunks.isEmpty()) {
                ByteBuffer head = chunks.peek();
                int count = Math.min(dst.remaining(), head.remaining());
                int oldLimit = head.limit();
                head.limit(head.position() + count);
                dst.put(head);
                head.limit(oldLimit);
                transferred += count;
                if (!head.hasRemaining()) {
                    chunks.poll();
                }
            }
            return transferred;
        }
    }

    private static final class WireChannel implements ByteChannel {
        private final Wire in;
        private final Wire out;

        WireChannel(Wire in, Wire out) {
            this.in = in;
            this.out = out;
        }

        @Override
        public int read(ByteBuffer dst) {
            return in.drainTo(dst);
        }

        @Override
        public int write(ByteBuffer src) {
            int count = src.remaining();
            out.add(src);
            return count;
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
            out.closed = true;
        }
    }
}
