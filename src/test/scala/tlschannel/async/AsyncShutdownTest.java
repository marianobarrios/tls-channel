package tlschannel.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import tlschannel.helpers.SocketGroups;
import tlschannel.helpers.SocketPairFactory;
import tlschannel.helpers.SslContextFactory;

@TestInstance(Lifecycle.PER_CLASS)
public class AsyncShutdownTest implements AsyncTestBase {

    private final SslContextFactory sslContextFactory = new SslContextFactory();
    private final SocketPairFactory factory = new SocketPairFactory(sslContextFactory.defaultContext());

    int bufferSize = 10;

    @Test
    public void testImmediateShutdown() throws InterruptedException {
        System.out.println("testImmediateShutdown():");
        AsynchronousTlsChannelGroup channelGroup = new AsynchronousTlsChannelGroup();
        int socketPairCount = 50;
        List<SocketGroups.AsyncSocketPair> socketPairs =
                factory.asyncN(null, channelGroup, socketPairCount, true, false);
        for (SocketGroups.AsyncSocketPair pair : socketPairs) {
            ByteBuffer writeBuffer = ByteBuffer.allocate(bufferSize);
            pair.client.external.write(writeBuffer);
            ByteBuffer readBuffer = ByteBuffer.allocate(bufferSize);
            pair.server.external.read(readBuffer);
        }

        assertFalse(channelGroup.isTerminated());

        channelGroup.shutdownNow();

        // terminated even after a relatively short timeout
        boolean terminated = channelGroup.awaitTermination(100, TimeUnit.MILLISECONDS);
        assertTrue(terminated);
        assertTrue(channelGroup.isTerminated());
        assertChannelGroupConsistency(channelGroup);

        printChannelGroupStatus(channelGroup);
    }

    @Test
    public void testNonImmediateShutdown() throws InterruptedException, IOException {
        System.out.println("testNonImmediateShutdown():");
        AsynchronousTlsChannelGroup channelGroup = new AsynchronousTlsChannelGroup();
        int socketPairCount = 50;
        List<SocketGroups.AsyncSocketPair> socketPairs =
                factory.asyncN(null, channelGroup, socketPairCount, true, false);
        for (SocketGroups.AsyncSocketPair pair : socketPairs) {
            ByteBuffer writeBuffer = ByteBuffer.allocate(bufferSize);
            pair.client.external.write(writeBuffer);
            ByteBuffer readBuffer = ByteBuffer.allocate(bufferSize);
            pair.server.external.read(readBuffer);
        }

        assertFalse(channelGroup.isTerminated());

        channelGroup.shutdown();

        {
            // not terminated even after a relatively long timeout
            boolean terminated = channelGroup.awaitTermination(2000, TimeUnit.MILLISECONDS);
            assertFalse(terminated);
            assertFalse(channelGroup.isTerminated());
        }

        for (SocketGroups.AsyncSocketPair pair : socketPairs) {
            pair.client.external.close();
            pair.server.external.close();
        }

        {
            // terminated even after a relatively short timeout
            boolean terminated = channelGroup.awaitTermination(100, TimeUnit.MILLISECONDS);
            assertTrue(terminated);
            assertTrue(channelGroup.isTerminated());
        }

        assertChannelGroupConsistency(channelGroup);

        assertEquals(0, channelGroup.getCancelledReadCount());
        assertEquals(0, channelGroup.getCancelledWriteCount());
        assertEquals(0, channelGroup.getFailedReadCount());
        assertEquals(0, channelGroup.getFailedWriteCount());

        printChannelGroupStatus(channelGroup);
    }
}
