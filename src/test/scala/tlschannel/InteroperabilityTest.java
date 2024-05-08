package tlschannel;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static tlschannel.util.InteroperabilityUtils.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import scala.Option;
import tlschannel.helpers.*;

@TestInstance(Lifecycle.PER_CLASS)
public class InteroperabilityTest {

    private final SslContextFactory sslContextFactory = new SslContextFactory();
    private final SocketPairFactory factory =
            new SocketPairFactory(sslContextFactory.defaultContext(), SslContextFactory.certificateCommonName);

    private final Random random = new Random();

    private final int dataSize = SslContextFactory.tlsMaxDataSize * 10;

    private final byte[] data = new byte[dataSize];

    {
        random.nextBytes(data);
    }

    private final int margin = random.nextInt(100);

    private void writerLoop(Writer writer, boolean renegotiate) {
        TestJavaUtil.cannotFail(() -> {
            int remaining = dataSize;
            while (remaining > 0) {
                if (renegotiate) writer.renegotiate();
                int chunkSize = random.nextInt(remaining) + 1; // 1 <= chunkSize <= remaining
                writer.write(data, dataSize - remaining, chunkSize);
                remaining -= chunkSize;
            }
        });
    }

    private void readerLoop(Reader reader) {
        TestJavaUtil.cannotFail(() -> {
            byte[] receivedData = new byte[dataSize + margin];
            int remaining = dataSize;
            while (remaining > 0) {
                int chunkSize = random.nextInt(remaining + margin) + 1; // 1 <= chunkSize <= remaining + margin
                int c = reader.read(receivedData, dataSize - remaining, chunkSize);
                assertNotEquals(-1, c, "read must not return -1 when there were bytesProduced remaining");
                assertTrue(c <= remaining);
                assertTrue(c > 0, "blocking read must return a positive number");
                remaining -= c;
            }
            assertEquals(0, remaining);
            assertArrayEquals(data, Arrays.copyOfRange(receivedData, 0, dataSize));
        });
    }

    /** Test a half-duplex interaction, with renegotiation before reversing the direction of the flow (as in HTTP)
     */
    private void halfDuplexStream(Writer serverWriter, Reader clientReader, Writer clientWriter, Reader serverReader)
            throws IOException, InterruptedException {
        Thread clientWriterThread = new Thread(() -> writerLoop(clientWriter, true), "client-writer");
        Thread serverWriterThread = new Thread(() -> writerLoop(serverWriter, true), "server-writer");
        Thread clientReaderThread = new Thread(() -> readerLoop(clientReader), "client-reader");
        Thread serverReaderThread = new Thread(() -> readerLoop(serverReader), "server-reader");
        serverReaderThread.start();
        clientWriterThread.start();
        serverReaderThread.join();
        clientWriterThread.join();
        clientReaderThread.start();
        // renegotiate three times, to test idempotency
        for (int i = 0; i < 3; i++) {
            serverWriter.renegotiate();
        }
        serverWriterThread.start();
        clientReaderThread.join();
        serverWriterThread.join();
        serverWriter.close();
        clientWriter.close();
    }

    /** Test a full-duplex interaction, without any renegotiation
     */
    private void fullDuplexStream(Writer serverWriter, Reader clientReader, Writer clientWriter, Reader serverReader)
            throws IOException, InterruptedException {
        Thread clientWriterThread = new Thread(() -> writerLoop(clientWriter, false), "client-writer");
        Thread serverWriterThread = new Thread(() -> writerLoop(serverWriter, false), "server-writer");
        Thread clientReaderThread = new Thread(() -> readerLoop(clientReader), "client-reader");
        Thread serverReaderThread = new Thread(() -> readerLoop(serverReader), "server-reader");
        serverReaderThread.start();
        clientWriterThread.start();
        clientReaderThread.start();
        serverWriterThread.start();
        serverReaderThread.join();
        clientWriterThread.join();
        clientReaderThread.join();
        serverWriterThread.join();
        clientWriter.close();
        serverWriter.close();
    }

    // OLD IO -> OLD IO

    // "old-io -> old-io (half duplex)
    @Test
    public void testOldToOldHalfDuplex() throws IOException, InterruptedException {
        SocketGroups.OldOldSocketPair sockerPair = factory.oldOld(Option.apply(null));
        halfDuplexStream(
                new SSLSocketWriter(sockerPair.server),
                new SocketReader(sockerPair.client),
                new SSLSocketWriter(sockerPair.client),
                new SocketReader(sockerPair.server));
    }

    // old-io -> old-io (full duplex)
    @Test
    public void testOldToOldFullDuplex() throws IOException, InterruptedException {
        SocketGroups.OldOldSocketPair sockerPair = factory.oldOld(Option.apply(null));
        fullDuplexStream(
                new SSLSocketWriter(sockerPair.server),
                new SocketReader(sockerPair.client),
                new SSLSocketWriter(sockerPair.client),
                new SocketReader(sockerPair.server));
    }

    // NIO -> OLD IO

    // nio -> old-io (half duplex)
    @Test
    public void testNioToOldHalfDuplex() throws IOException, InterruptedException {
        SocketGroups.NioOldSocketPair socketPair = factory.nioOld(Option.apply(null));
        halfDuplexStream(
                new SSLSocketWriter(socketPair.server),
                new ByteChannelReader(socketPair.client.tls),
                new TlsChannelWriter(socketPair.client.tls),
                new SocketReader(socketPair.server));
    }

    // nio -> old-io (full duplex)
    @Test
    public void testNioToOldFullDuplex() throws IOException, InterruptedException {
        SocketGroups.NioOldSocketPair socketPair = factory.nioOld(Option.apply(null));
        fullDuplexStream(
                new SSLSocketWriter(socketPair.server),
                new ByteChannelReader(socketPair.client.tls),
                new TlsChannelWriter(socketPair.client.tls),
                new SocketReader(socketPair.server));
    }

    // OLD IO -> NIO

    // old-io -> nio (half duplex)
    @Test
    public void testOldToNioHalfDuplex() throws IOException, InterruptedException {
        SocketGroups.OldNioSocketPair socketPair = factory.oldNio(Option.apply(null));
        halfDuplexStream(
                new TlsChannelWriter(socketPair.server.tls),
                new SocketReader(socketPair.client),
                new SSLSocketWriter(socketPair.client),
                new ByteChannelReader(socketPair.server.tls));
    }

    // old-io -> nio (full duplex)
    @Test
    public void testOldToNioFullDuplex() throws IOException, InterruptedException {
        SocketGroups.OldNioSocketPair socketPair = factory.oldNio(Option.apply(null));
        fullDuplexStream(
                new TlsChannelWriter(socketPair.server.tls),
                new SocketReader(socketPair.client),
                new SSLSocketWriter(socketPair.client),
                new ByteChannelReader(socketPair.server.tls));
    }
}
