package tlschannel.async;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import tlschannel.helpers.SocketPairFactory;
import tlschannel.helpers.SslContextFactory;

@TestInstance(Lifecycle.PER_CLASS)
public class AsyncQuickCloseTest implements AsyncTestBase {

    private final SslContextFactory sslContextFactory = new SslContextFactory();
    private final SocketPairFactory factory = new SocketPairFactory(sslContextFactory.defaultContext());

    /*
     * Closing sockets registered in an asynchronous channel group is inherently racy, using repetitions to try to catch
     * most races.
     */
    private final int repetitions = 250;

    private final int bufferSize = 10000;

    // see https://github.com/marianobarrios/tls-channel/issues/34
    // immediate closings after registration
    @Test
    public void testImmediateClose() throws IOException {
        AsynchronousTlsChannelGroup channelGroup = new AsynchronousTlsChannelGroup();
        for (int i = 1; i <= repetitions; i++) {
            // create (and register) channels and close immediately
            tlschannel.helpers.SocketGroups.AsyncSocketPair socketPair = factory.async(null, channelGroup, true, false);
            socketPair.server.external.close();
            socketPair.client.external.close();

            // try read
            ByteBuffer readBuffer = ByteBuffer.allocate(bufferSize);
            Future<Integer> readFuture = socketPair.server.external.read(readBuffer);
            Exception e1 = Assertions.assertThrows(ExecutionException.class, () -> readFuture.get());
            assertInstanceOf(ClosedChannelException.class, e1.getCause());

            // try write
            Future<Integer> writeFuture = socketPair.client.external.write(ByteBuffer.wrap(new byte[] {1}));
            Exception e2 = Assertions.assertThrows(ExecutionException.class, () -> writeFuture.get());
            assertInstanceOf(ClosedChannelException.class, e2.getCause());
        }
        assertTrue(channelGroup.isAlive());
        channelGroup.shutdown();
        assertChannelGroupConsistency(channelGroup);
    }

    // immediate closings after registration, even if we close the raw channel
    @Test
    public void testRawImmediateClosing() throws IOException {
        AsynchronousTlsChannelGroup channelGroup = new AsynchronousTlsChannelGroup();
        for (int i = 1; i <= repetitions; i++) {
            // create (and register) channels and close immediately
            tlschannel.helpers.SocketGroups.AsyncSocketPair socketPair = factory.async(null, channelGroup, true, false);
            socketPair.server.plain.close();
            socketPair.client.plain.close();

            // try read
            ByteBuffer readBuffer = ByteBuffer.allocate(bufferSize);
            Future<Integer> readFuture = socketPair.server.external.read(readBuffer);
            Exception readEx = Assertions.assertThrows(ExecutionException.class, () -> readFuture.get());
            assertInstanceOf(ClosedChannelException.class, readEx.getCause());

            // try write
            Future<Integer> writeFuture = socketPair.client.external.write(ByteBuffer.wrap(new byte[] {1}));
            Exception writeEx = Assertions.assertThrows(ExecutionException.class, () -> writeFuture.get());
            assertInstanceOf(ClosedChannelException.class, writeEx.getCause());
        }
        assertTrue(channelGroup.isAlive());
        channelGroup.shutdown();
        assertChannelGroupConsistency(channelGroup);
    }
}
