package tlschannel.async;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import tlschannel.helpers.SocketGroups;
import tlschannel.helpers.SocketPairFactory;
import tlschannel.helpers.SslContextFactory;

@TestInstance(Lifecycle.PER_CLASS)
public class AsyncTimeoutTest implements AsyncTestBase {

    SslContextFactory sslContextFactory = new SslContextFactory();
    SocketPairFactory factory = new SocketPairFactory(sslContextFactory.defaultContext());

    private static final int bufferSize = 10;

    private static final int repetitions = 50;

    // scheduled timeout
    @Test
    public void testScheduledTimeout() throws IOException {
        System.out.println("testScheduledTimeout()");
        AsynchronousTlsChannelGroup channelGroup = new AsynchronousTlsChannelGroup();
        LongAdder successWrites = new LongAdder();
        LongAdder successReads = new LongAdder();
        for (int i = 1; i <= repetitions; i++) {
            int socketPairCount = 50;
            List<SocketGroups.AsyncSocketPair> socketPairs =
                    factory.asyncN(null, channelGroup, socketPairCount, true, false);
            CountDownLatch latch = new CountDownLatch(socketPairCount * 2);
            for (SocketGroups.AsyncSocketPair pair : socketPairs) {
                ByteBuffer writeBuffer = ByteBuffer.allocate(bufferSize);
                AtomicBoolean clientDone = new AtomicBoolean();
                pair.client.external.write(
                        writeBuffer, 50, TimeUnit.MILLISECONDS, null, new CompletionHandler<Integer, Object>() {
                            @Override
                            public void failed(Throwable exc, Object attachment) {
                                if (!clientDone.compareAndSet(false, true)) {
                                    Assertions.fail();
                                }
                                latch.countDown();
                            }

                            @Override
                            public void completed(Integer result, Object attachment) {
                                if (!clientDone.compareAndSet(false, true)) {
                                    Assertions.fail();
                                }
                                latch.countDown();
                                successWrites.increment();
                            }
                        });
                ByteBuffer readBuffer = ByteBuffer.allocate(bufferSize);
                AtomicBoolean serverDone = new AtomicBoolean();
                pair.server.external.read(
                        readBuffer, 100, TimeUnit.MILLISECONDS, null, new CompletionHandler<Integer, Object>() {
                            @Override
                            public void failed(Throwable exc, Object attachment) {
                                if (!serverDone.compareAndSet(false, true)) {
                                    Assertions.fail();
                                }
                                latch.countDown();
                            }

                            @Override
                            public void completed(Integer result, Object attachment) {
                                if (!serverDone.compareAndSet(false, true)) {
                                    Assertions.fail();
                                }
                                latch.countDown();
                                successReads.increment();
                            }
                        });
            }
            try {
                latch.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            for (SocketGroups.AsyncSocketPair pair : socketPairs) {
                pair.client.external.close();
                pair.server.external.close();
            }
        }

        shutdownChannelGroup(channelGroup);
        assertChannelGroupConsistency(channelGroup);

        assertEquals(0, channelGroup.getFailedReadCount());
        assertEquals(0, channelGroup.getFailedWriteCount());

        assertEquals(channelGroup.getSuccessfulWriteCount(), successWrites.longValue());
        assertEquals(channelGroup.getSuccessfulReadCount(), successReads.longValue());

        System.out.printf("success writes:     %8d\n", successWrites.longValue());
        System.out.printf("success reads:      %8d\n", successReads.longValue());
        printChannelGroupStatus(channelGroup);
    }

    // triggered timeout
    @Test
    public void testTriggeredTimeout() throws IOException {
        System.out.println("testScheduledTimeout()");
        AsynchronousTlsChannelGroup channelGroup = new AsynchronousTlsChannelGroup();
        int successfulWriteCancellations = 0;
        int successfulReadCancellations = 0;
        for (int i = 1; i <= repetitions; i++) {
            int socketPairCount = 50;
            List<SocketGroups.AsyncSocketPair> socketPairs =
                    factory.asyncN(null, channelGroup, socketPairCount, true, false);

            for (SocketGroups.AsyncSocketPair pair : socketPairs) {
                ByteBuffer writeBuffer = ByteBuffer.allocate(bufferSize);
                Future<Integer> writeFuture = pair.client.external.write(writeBuffer);
                if (writeFuture.cancel(true)) {
                    successfulWriteCancellations += 1;
                }
            }

            for (SocketGroups.AsyncSocketPair pair : socketPairs) {
                ByteBuffer readBuffer = ByteBuffer.allocate(bufferSize);
                Future<Integer> readFuture = pair.server.external.read(readBuffer);
                if (readFuture.cancel(true)) {
                    successfulReadCancellations += 1;
                }
            }

            for (SocketGroups.AsyncSocketPair pair : socketPairs) {
                pair.client.external.close();
                pair.server.external.close();
            }
        }
        shutdownChannelGroup(channelGroup);
        assertChannelGroupConsistency(channelGroup);

        assertEquals(0, channelGroup.getFailedReadCount());
        assertEquals(0, channelGroup.getFailedWriteCount());

        assertEquals(channelGroup.getCancelledWriteCount(), successfulWriteCancellations);
        assertEquals(channelGroup.getCancelledReadCount(), successfulReadCancellations);

        System.out.printf("success writes:     %8d\n", channelGroup.getSuccessfulWriteCount());
        System.out.printf("success reads:      %8d\n", channelGroup.getSuccessfulReadCount());
    }
}
