package tlschannel.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import tlschannel.helpers.AsyncSocketPair;
import tlschannel.helpers.SocketPairFactory;
import tlschannel.helpers.SslContextFactory;

@TestInstance(Lifecycle.PER_CLASS)
public class AsyncCloseTest implements AsyncTestBase {

    private final SslContextFactory sslContextFactory = new SslContextFactory();
    private final SocketPairFactory factory = new SocketPairFactory(sslContextFactory.defaultContext());

    private static final int bufferSize = 10000;

    /*
     * Closing sockets registered in an asynchronous channel group is inherently racy, using repetitions to try to catch
     * most races.
     */
    private static final int repetitions = 250;

    // should throw an CancellationException (or ClosedChannelException) when closing the group while reading
    @Test
    public void testClosingWhileReading() throws IOException, InterruptedException {
        for (int i = 0; i < repetitions; i++) {
            AsynchronousTlsChannelGroup channelGroup = new AsynchronousTlsChannelGroup();
            AsyncSocketPair socketPair = factory.async(null, channelGroup, true, false);

            ByteBuffer readBuffer = ByteBuffer.allocate(bufferSize);
            Future<Integer> readFuture = socketPair.server().external().read(readBuffer);

            socketPair.server().external().close();

            try {
                readFuture.get(1000, TimeUnit.MILLISECONDS);
            } catch (CancellationException ce) {
                // give time to adders to converge
                Thread.sleep(10);
                assertEquals(1, channelGroup.getCancelledReadCount());
                assertEquals(0, channelGroup.getFailedReadCount());
            } catch (ExecutionException ee) {
                // give time to adders to converge
                Thread.sleep(10);
                assertInstanceOf(ClosedChannelException.class, ee.getCause());
                assertEquals(0, channelGroup.getCancelledReadCount());
                assertEquals(1, channelGroup.getFailedReadCount());
            } catch (Exception e) {
                Assertions.fail(e);
            }

            socketPair.client().external().close();
            shutdownChannelGroup(channelGroup);
            assertChannelGroupConsistency(channelGroup);
            assertEquals(0, channelGroup.getSuccessfulReadCount());
            channelGroup.shutdown();
        }
    }

    // should throw an CancellationException (or ClosedChannelException) when closing the group while reading, even if
    // we close the raw channel
    @Test
    public void testRawClosingWhileReading() throws IOException, InterruptedException {
        for (int i = 0; i < repetitions; i++) {
            AsynchronousTlsChannelGroup channelGroup = new AsynchronousTlsChannelGroup();
            AsyncSocketPair socketPair = factory.async(null, channelGroup, true, false);

            ByteBuffer readBuffer = ByteBuffer.allocate(bufferSize);
            Future<Integer> readFuture = socketPair.server().external().read(readBuffer);

            // important: closing the raw socket
            socketPair.server().plain().close();

            try {
                readFuture.get(1000, TimeUnit.MILLISECONDS);
            } catch (CancellationException ce) {
                // give time to adders to converge
                Thread.sleep(10);
                assertEquals(1, channelGroup.getCancelledReadCount());
                assertEquals(0, channelGroup.getFailedReadCount());
            } catch (ExecutionException ee) {
                // give time to adders to converge
                Thread.sleep(10);
                assertInstanceOf(ClosedChannelException.class, ee.getCause());
                assertEquals(0, channelGroup.getCancelledReadCount());
                assertEquals(1, channelGroup.getFailedReadCount());
            } catch (Exception e) {
                Assertions.fail(e);
            }

            socketPair.client().external().close();
            shutdownChannelGroup(channelGroup);
            assertChannelGroupConsistency(channelGroup);
            assertEquals(0, channelGroup.getSuccessfulReadCount());
            channelGroup.shutdown();
        }
    }
}
