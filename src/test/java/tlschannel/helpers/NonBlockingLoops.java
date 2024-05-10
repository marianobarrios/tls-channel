package tlschannel.helpers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tlschannel.NeedsReadException;
import tlschannel.NeedsTaskException;
import tlschannel.NeedsWriteException;
import tlschannel.helpers.SocketGroups.SocketGroup;
import tlschannel.helpers.SocketGroups.SocketPair;

public class NonBlockingLoops {

    interface Endpoint {
        SelectionKey key();

        int remaining();
    }

    public static class WriterEndpoint implements Endpoint {

        private final SocketGroup socketGroup;
        private SelectionKey key;
        private final SplittableRandom random = new SplittableRandom(Loops.seed);
        private final ByteBuffer buffer = ByteBuffer.allocate(Loops.bufferSize);
        private int remaining;

        public WriterEndpoint(SocketGroup socketGroup, SelectionKey key, int remaining) {
            this.socketGroup = socketGroup;
            this.key = key;
            this.remaining = remaining;
            buffer.flip();
        }

        @Override
        public SelectionKey key() {
            return key;
        }

        @Override
        public int remaining() {
            return remaining;
        }
    }

    public static class ReaderEndpoint implements Endpoint {
        private final SocketGroup socketGroup;
        private SelectionKey key;
        private final ByteBuffer buffer = ByteBuffer.allocate(Loops.bufferSize);
        private final MessageDigest digest;
        private int remaining;

        public ReaderEndpoint(SocketGroup socketGroup, SelectionKey key, int remaining) {
            this.socketGroup = socketGroup;
            this.key = key;
            this.remaining = remaining;
            try {
                this.digest = MessageDigest.getInstance(Loops.hashAlgorithm);
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public SelectionKey key() {
            return key;
        }

        @Override
        public int remaining() {
            return remaining;
        }
    }

    public static class Report {
        public final int selectorCycles;
        public final int needReadCount;
        public final int needWriteCount;
        public final int renegotiationCount;
        public final int asyncTasksRun;
        public final Duration totalAsyncTaskRunningTime;

        public Report(
                int selectorCycles,
                int needReadCount,
                int needWriteCount,
                int renegotiationCount,
                int asyncTasksRun,
                Duration totalAsyncTaskRunningTime) {
            this.selectorCycles = selectorCycles;
            this.needReadCount = needReadCount;
            this.needWriteCount = needWriteCount;
            this.renegotiationCount = renegotiationCount;
            this.asyncTasksRun = asyncTasksRun;
            this.totalAsyncTaskRunningTime = totalAsyncTaskRunningTime;
        }

        public void print() {
            System.out.printf("Selector cycles:%s\n", selectorCycles);
            System.out.printf("NeedRead count: %s\n", needReadCount);
            System.out.printf("NeedWrite count: %s\n", needWriteCount);
            System.out.printf("Renegotiation count: %s\n", renegotiationCount);
            System.out.printf("Asynchronous tasks run: %s\n", asyncTasksRun);
            System.out.printf("Total asynchronous task running time: %s ms\n", totalAsyncTaskRunningTime.toMillis());
        }
    }

    public static Report loop(List<SocketPair> socketPairs, int dataSize, boolean renegotiate) {
        try {
            int totalConnections = socketPairs.size();
            Selector selector = Selector.open();
            ExecutorService executor =
                    Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() - 1);

            ConcurrentLinkedQueue<Endpoint> readyTaskSockets = new ConcurrentLinkedQueue<>();

            List<WriterEndpoint> writers = socketPairs.stream()
                    .map(pair -> {
                        try {
                            pair.client.plain.configureBlocking(false);
                            WriterEndpoint clientEndpoint = new WriterEndpoint(pair.client, null, dataSize);
                            clientEndpoint.key =
                                    pair.client.plain.register(selector, SelectionKey.OP_WRITE, clientEndpoint);
                            return clientEndpoint;
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toList());

            List<ReaderEndpoint> readers = socketPairs.stream()
                    .map(pair -> {
                        try {
                            pair.server.plain.configureBlocking(false);
                            ReaderEndpoint serverEndpoint = new ReaderEndpoint(pair.server, null, dataSize);
                            serverEndpoint.key =
                                    pair.server.plain.register(selector, SelectionKey.OP_READ, serverEndpoint);
                            return serverEndpoint;
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .collect(Collectors.toList());

            // var allEndpoints = writers ++ readers;

            int taskCount = 0;
            int needReadCount = 0;
            int needWriteCount = 0;
            int selectorCycles = 0;
            int renegotiationCount = 0;
            int maxRenegotiations = renegotiate ? totalConnections * 2 * 20 : 0;

            Random random = new Random();

            LongAdder totalTaskTimeNanos = new LongAdder();

            byte[] dataHash = Loops.expectedBytesHash.apply(dataSize);

            while (readers.stream().anyMatch(r -> r.remaining > 0)
                    || writers.stream().anyMatch(r -> r.remaining > 0)) {
                selectorCycles += 1;
                selector.select(); // block

                for (Endpoint endpoint : Stream.concat(
                                getSelectedEndpoints(selector), TestUtil.removeAndCollect(readyTaskSockets.iterator()))
                        .collect(Collectors.toList())) {
                    try {
                        if (endpoint instanceof WriterEndpoint) {
                            WriterEndpoint writer = (WriterEndpoint) endpoint;
                            do {
                                if (renegotiationCount < maxRenegotiations) {
                                    if (random.nextBoolean()) {
                                        renegotiationCount += 1;
                                        writer.socketGroup.tls.renegotiate();
                                    }
                                }
                                if (!writer.buffer.hasRemaining()) {
                                    TestUtil.nextBytes(writer.random, writer.buffer.array());
                                    writer.buffer.position(0);
                                    writer.buffer.limit(Math.min(writer.buffer.capacity(), writer.remaining));
                                }
                                int oldPosition = writer.buffer.position();
                                try {
                                    int c = writer.socketGroup.external.write(writer.buffer);
                                    assertTrue(c >= 0); // the necessity of blocking is communicated with exceptions
                                } finally {
                                    int bytesWritten = writer.buffer.position() - oldPosition;
                                    writer.remaining -= bytesWritten;
                                }

                            } while (writer.remaining > 0);

                        } else if (endpoint instanceof ReaderEndpoint) {
                            ReaderEndpoint reader = (ReaderEndpoint) endpoint;
                            do {
                                reader.buffer.clear();
                                int c = reader.socketGroup.external.read(reader.buffer);
                                assertTrue(c > 0); // the necessity of blocking is communicated with exceptions
                                reader.digest.update(reader.buffer.array(), 0, c);
                                reader.remaining -= c;
                            } while (reader.remaining > 0);
                        } else {
                            throw new IllegalArgumentException();
                        }
                    } catch (NeedsWriteException e) {
                        needWriteCount += 1;
                        endpoint.key().interestOps(SelectionKey.OP_WRITE);
                    } catch (NeedsReadException e) {
                        needReadCount += 1;
                        endpoint.key().interestOps(SelectionKey.OP_READ);
                    } catch (NeedsTaskException e) {
                        Runnable r = () -> {
                            long start = System.nanoTime();
                            e.getTask().run();
                            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
                            selector.wakeup();
                            readyTaskSockets.add(endpoint);
                            totalTaskTimeNanos.add(elapsed.toNanos());
                        };
                        executor.submit(r);
                        taskCount += 1;
                    }
                }
            }

            for (SocketPair socketPair : socketPairs) {
                socketPair.client.external.close();
                socketPair.server.external.close();
                SocketPairFactory.checkDeallocation(socketPair);
            }

            for (ReaderEndpoint reader : readers) {
                assertArrayEquals(reader.digest.digest(), dataHash);
            }

            return new Report(
                    selectorCycles,
                    needReadCount,
                    needWriteCount,
                    renegotiationCount,
                    taskCount,
                    Duration.ofNanos(totalTaskTimeNanos.longValue()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Stream<Endpoint> getSelectedEndpoints(Selector selector) {
        List<Endpoint> builder = new ArrayList<>();
        Iterator<SelectionKey> it = selector.selectedKeys().iterator();
        while (it.hasNext()) {
            SelectionKey key = it.next();
            key.interestOps(0); // delete all operations
            builder.add((Endpoint) key.attachment());
            it.remove();
        }
        return builder.stream();
    }
}
