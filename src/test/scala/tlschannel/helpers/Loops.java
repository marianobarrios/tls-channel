package tlschannel.helpers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.SplittableRandom;
import java.util.function.Function;
import java.util.logging.Logger;
import tlschannel.helpers.SocketGroups.*;

public class Loops {

    private static final Logger logger = Logger.getLogger(Loops.class.getName());

    public static final long seed = 143000953L;

    /*
     * Note that it is necessary to use a multiple of 4 as buffer size for writing.
     * This is because the bytesProduced to write are generated using Random.nextBytes, that
     * always consumes full (4 byte) integers. A multiple of 4 then prevents "holes"
     * in the random sequence.
     */
    public static final int bufferSize = 4 * 5000;

    private static final int renegotiatePeriod = 10000;
    public static final String hashAlgorithm = "MD5"; // for speed

    /** Test a half-duplex interaction, with (optional) renegotiation before reversing the direction of the flow (as in
     * HTTP)
     */
    public static void halfDuplex(SocketPair socketPair, int dataSize, boolean renegotiation, boolean scattering) {
        Thread clientWriterThread = new Thread(
                () -> Loops.writerLoop(dataSize, socketPair.client, renegotiation, scattering, false, false),
                "client-writer");
        Thread serverReaderThread = new Thread(
                () -> Loops.readerLoop(dataSize, socketPair.server, scattering, false, false), "server-reader");
        Thread serverWriterThread = new Thread(
                () -> Loops.writerLoop(dataSize, socketPair.server, renegotiation, scattering, true, true),
                "server-writer");
        Thread clientReaderThread = new Thread(
                () -> Loops.readerLoop(dataSize, socketPair.client, scattering, true, true), "client-reader");

        try {
            serverReaderThread.start();
            clientWriterThread.start();

            serverReaderThread.join();
            clientWriterThread.join();

            clientReaderThread.start();
            serverWriterThread.start();

            clientReaderThread.join();
            serverWriterThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        SocketPairFactory.checkDeallocation(socketPair);
    }

    public static void fullDuplex(SocketPair socketPair, int dataSize) {
        Thread clientWriterThread = new Thread(
                () -> Loops.writerLoop(dataSize, socketPair.client, false, false, false, false), "client-writer");
        Thread serverWriterThread = new Thread(
                () -> Loops.writerLoop(dataSize, socketPair.server, false, false, false, false), "server-write");
        Thread clientReaderThread =
                new Thread(() -> Loops.readerLoop(dataSize, socketPair.client, false, false, false), "client-reader");
        Thread serverReaderThread =
                new Thread(() -> Loops.readerLoop(dataSize, socketPair.server, false, false, false), "server-reader");

        try {
            serverReaderThread.start();
            clientWriterThread.start();
            clientReaderThread.start();
            serverWriterThread.start();

            serverReaderThread.join();
            clientWriterThread.join();
            clientReaderThread.join();
            serverWriterThread.join();

            socketPair.client.external.close();
            socketPair.server.external.close();

        } catch (InterruptedException | IOException e) {
            throw new RuntimeException(e);
        }

        SocketPairFactory.checkDeallocation(socketPair);
    }

    public static void writerLoop(
            int size,
            SocketGroup socketGroup,
            boolean renegotiate,
            boolean scattering,
            boolean shutdown,
            boolean close) {
        TestUtil.cannotFail(() -> {
            logger.fine(() -> String.format(
                    "Starting writer loop, size: %s, scattering: %s, renegotiate: %s", size, scattering, renegotiate));
            SplittableRandom random = new SplittableRandom(seed);
            int bytesSinceRenegotiation = 0;
            int bytesRemaining = size;
            byte[] bufferArray = new byte[bufferSize];
            while (bytesRemaining > 0) {
                ByteBuffer buffer = ByteBuffer.wrap(bufferArray, 0, Math.min(bufferSize, bytesRemaining));
                TestUtil.nextBytes(random, buffer.array());
                while (buffer.hasRemaining()) {
                    if (renegotiate && bytesSinceRenegotiation > renegotiatePeriod) {
                        socketGroup.tls.renegotiate();
                        bytesSinceRenegotiation = 0;
                    }
                    int c;
                    if (scattering) {
                        c = (int) socketGroup.tls.write(multiWrap(buffer));
                    } else {
                        c = socketGroup.external.write(buffer);
                    }
                    assertTrue(c > 0, "blocking write must return a positive number");
                    bytesSinceRenegotiation += c;
                    bytesRemaining -= c;
                    assertTrue(bytesRemaining >= 0);
                }
            }
            if (shutdown) socketGroup.tls.shutdown();
            if (close) socketGroup.external.close();
            logger.fine("Finalizing writer loop");
        });
    }

    public static void readerLoop(
            int size, SocketGroup socketGroup, boolean gathering, boolean readEof, boolean close) {

        TestUtil.cannotFail(() -> {
            logger.fine(() -> String.format("Starting reader loop. Size: $size, gathering: %s", gathering));
            byte[] readArray = new byte[bufferSize];
            int bytesRemaining = size;
            MessageDigest digest = MessageDigest.getInstance(hashAlgorithm);
            while (bytesRemaining > 0) {
                ByteBuffer readBuffer = ByteBuffer.wrap(readArray, 0, Math.min(bufferSize, bytesRemaining));
                int c;
                if (gathering) {
                    c = (int) socketGroup.tls.read(multiWrap(readBuffer));
                } else {
                    c = socketGroup.external.read(readBuffer);
                }
                assertTrue(c > 0, "blocking read must return a positive number");
                digest.update(readBuffer.array(), 0, readBuffer.position());
                bytesRemaining -= c;
                assertTrue(bytesRemaining >= 0);
            }
            if (readEof) assertEquals(-1, socketGroup.external.read(ByteBuffer.wrap(readArray)));
            byte[] actual = digest.digest();
            assertArrayEquals(expectedBytesHash.apply(size), actual);
            if (close) socketGroup.external.close();
            logger.fine("Finalizing reader loop");
        });
    }

    private static byte[] hash(int size) {
        try {
            MessageDigest digest = MessageDigest.getInstance(hashAlgorithm);
            SplittableRandom random = new SplittableRandom(seed);
            int generated = 0;
            int bufferSize = 4 * 1024;
            byte[] array = new byte[bufferSize];
            while (generated < size) {
                TestUtil.nextBytes(random, array);
                int pending = size - generated;
                digest.update(array, 0, Math.min(bufferSize, pending));
                generated += bufferSize;
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static final Function<Integer, byte[]> expectedBytesHash = new TestUtil.Memo<>(Loops::hash)::apply;

    private static ByteBuffer[] multiWrap(ByteBuffer buffer) {
        return new ByteBuffer[] {ByteBuffer.allocate(0), buffer, ByteBuffer.allocate(0)};
    }
}
