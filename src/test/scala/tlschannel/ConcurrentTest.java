package tlschannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import scala.Option;
import tlschannel.helpers.*;

@TestInstance(Lifecycle.PER_CLASS)
public class ConcurrentTest {

    private static final Logger logger = Logger.getLogger(ConcurrentTest.class.getName());

    private final SslContextFactory sslContextFactory = new SslContextFactory();
    private final SocketPairFactory factory = new SocketPairFactory(sslContextFactory.defaultContext());
    private final int dataSize = 250_000_000;
    private final int bufferSize = 2000;

    /** Test several parties writing concurrently
     */
    // write-side thread safety
    @Test
    public void testWriteSide() throws IOException {
        SocketPair socketPair = factory.nioNio(Option.apply(null), Option.apply(null), true, false, Option.apply(null));
        Thread clientWriterThread1 =
                new Thread(() -> writerLoop(dataSize, 'a', socketPair.client()), "client-writer-1");
        Thread clientWriterThread2 =
                new Thread(() -> writerLoop(dataSize, 'b', socketPair.client()), "client-writer-2");
        Thread clientWriterThread3 =
                new Thread(() -> writerLoop(dataSize, 'c', socketPair.client()), "client-writer-3");
        Thread clientWriterThread4 =
                new Thread(() -> writerLoop(dataSize, 'd', socketPair.client()), "client-writer-4");
        Thread serverReaderThread = new Thread(() -> readerLoop(dataSize * 4, socketPair.server()), "server-reader");
        Stream.of(
                        serverReaderThread,
                        clientWriterThread1,
                        clientWriterThread2,
                        clientWriterThread3,
                        clientWriterThread4)
                .forEach(t -> t.start());
        Stream.of(clientWriterThread1, clientWriterThread2, clientWriterThread3, clientWriterThread4)
                .forEach(t -> joinInterruptible(t));
        socketPair.client().external().close();
        joinInterruptible(serverReaderThread);
        SocketPairFactory.checkDeallocation(socketPair);
    }

    // read-size thread-safety
    @Test
    public void testReadSide() throws IOException {
        SocketPair socketPair = factory.nioNio(Option.apply(null), Option.apply(null), true, false, Option.apply(null));
        Thread clientWriterThread = new Thread(() -> writerLoop(dataSize, 'a', socketPair.client()), "client-writer");
        AtomicLong totalRead = new AtomicLong();
        Thread serverReaderThread1 =
                new Thread(() -> readerLoopUntilEof(socketPair.server(), totalRead), "server-reader-1");
        Thread serverReaderThread2 =
                new Thread(() -> readerLoopUntilEof(socketPair.server(), totalRead), "server-reader-2");
        Stream.of(serverReaderThread1, serverReaderThread2, clientWriterThread).forEach(t -> t.start());
        joinInterruptible(clientWriterThread);
        socketPair.client().external().close();
        Stream.of(serverReaderThread1, serverReaderThread2).forEach(t -> joinInterruptible(t));
        SocketPairFactory.checkDeallocation(socketPair);
        assertEquals(dataSize, totalRead.get());
    }

    private void writerLoop(int size, char ch, SocketGroup socketGroup) {
        TestUtil.cannotFail(() -> {
            try {
                logger.fine(() -> String.format("Starting writer loop, size: %s", size));
                int bytesRemaining = size;
                byte[] bufferArray = new byte[bufferSize];
                Arrays.fill(bufferArray, (byte) ch);
                while (bytesRemaining > 0) {
                    ByteBuffer buffer = ByteBuffer.wrap(bufferArray, 0, Math.min(bufferSize, bytesRemaining));
                    while (buffer.hasRemaining()) {
                        int c = socketGroup.external().write(buffer);
                        assertTrue(c > 0, "blocking write must return a positive number");
                        bytesRemaining -= c;
                        assertTrue(bytesRemaining >= 0);
                    }
                }
                logger.fine("Finalizing writer loop");
                return null;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void readerLoop(int size, SocketGroup socketGroup) {
        TestUtil.cannotFail(() -> {
            try {
                logger.fine(() -> String.format("Starting reader loop, size: %s", size));
                byte[] readArray = new byte[bufferSize];
                int bytesRemaining = size;
                while (bytesRemaining > 0) {
                    ByteBuffer readBuffer = ByteBuffer.wrap(readArray, 0, Math.min(bufferSize, bytesRemaining));
                    int c = socketGroup.external().read(readBuffer);
                    assertTrue(c > 0, "blocking read must return a positive number");
                    bytesRemaining -= c;
                    assertTrue(bytesRemaining >= 0);
                }
                logger.fine("Finalizing reader loop");
                return null;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void readerLoopUntilEof(SocketGroup socketGroup, AtomicLong accumulator) {
        TestUtil.cannotFail(() -> {
            try {
                logger.fine("Starting reader loop");
                byte[] readArray = new byte[bufferSize];
                while (true) {
                    ByteBuffer readBuffer = ByteBuffer.wrap(readArray, 0, bufferSize);
                    int c = socketGroup.external().read(readBuffer);
                    if (c == -1) {
                        logger.fine("Finalizing reader loop");
                        return null;
                    }
                    assertTrue(c > 0, "blocking read must return a positive number");
                    accumulator.addAndGet(c);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private static void joinInterruptible(Thread t) {
        try {
            t.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
