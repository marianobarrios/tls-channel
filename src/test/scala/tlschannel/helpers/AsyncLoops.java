package tlschannel.helpers;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import tlschannel.helpers.SocketGroups.AsyncSocketGroup;
import tlschannel.helpers.SocketGroups.AsyncSocketPair;

public class AsyncLoops {

    private static final Logger logger = Logger.getLogger(AsyncLoops.class.getName());

    private interface Endpoint {
        int remaining();

        Optional<Throwable> exception();
    }

    private static class WriterEndpoint implements Endpoint {
        private final AsyncSocketGroup socketGroup;
        private final SplittableRandom random = new SplittableRandom(Loops.seed());
        private final ByteBuffer buffer = ByteBuffer.allocate(Loops.bufferSize());
        private int remaining;
        private Optional<Throwable> exception = Optional.empty();

        public WriterEndpoint(AsyncSocketGroup socketGroup, int remaining) {
            this.socketGroup = socketGroup;
            this.remaining = remaining;
            buffer.flip();
        }

        @Override
        public int remaining() {
            return remaining;
        }

        public Optional<Throwable> exception() {
            return exception;
        }
    }

    private static class ReaderEndpoint implements Endpoint {
        private final AsyncSocketGroup socketGroup;
        private final ByteBuffer buffer = ByteBuffer.allocate(Loops.bufferSize());
        private final MessageDigest digest;
        private int remaining;
        private Optional<Throwable> exception = Optional.empty();

        public ReaderEndpoint(AsyncSocketGroup socketGroup, int remaining) {
            this.socketGroup = socketGroup;
            this.remaining = remaining;
            try {
                digest = MessageDigest.getInstance(Loops.hashAlgorithm());
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public int remaining() {
            return remaining;
        }

        public Optional<Throwable> exception() {
            return exception;
        }
    }

    public static class Report {
        final long dequeueCycles;
        final long completedReads;
        final long failedReads;
        final long completedWrites;
        final long failedWrites;

        public Report(
                long dequeueCycles, long completedReads, long failedReads, long completedWrites, long failedWrites) {
            this.dequeueCycles = dequeueCycles;
            this.completedReads = completedReads;
            this.failedReads = failedReads;
            this.completedWrites = completedWrites;
            this.failedWrites = failedWrites;
        }

        public void print() {
            System.out.print("test loop:\n");
            System.out.printf("  dequeue cycles:   %8d\n", dequeueCycles);
            System.out.printf("  completed reads:  %8d\n", completedReads);
            System.out.printf("  failed reads:     %8d\n", failedReads);
            System.out.printf("  completed writes: %8d\n", completedWrites);
            System.out.printf("  failed writes:    %8d\n", failedWrites);
        }
    }

    public static Report loop(List<AsyncSocketPair> socketPairs, int dataSize) throws Throwable {
        logger.fine(() -> "starting async loop - pair count: " + socketPairs.size());

        int dequeueCycles = 0;
        LongAdder completedReads = new LongAdder();
        LongAdder failedReads = new LongAdder();
        LongAdder completedWrites = new LongAdder();
        LongAdder failedWrites = new LongAdder();

        LinkedBlockingQueue<Endpoint> endpointQueue = new LinkedBlockingQueue<>();
        byte[] dataHash = Loops.expectedBytesHash().apply(dataSize);

        List<WriterEndpoint> clientEndpoints = socketPairs.stream()
                .map(p -> new WriterEndpoint(p.client, dataSize))
                .collect(Collectors.toList());

        List<ReaderEndpoint> serverEndpoints = socketPairs.stream()
                .map(p -> new ReaderEndpoint(p.server, dataSize))
                .collect(Collectors.toList());

        for (Endpoint endpoint : clientEndpoints) {
            endpointQueue.put(endpoint);
        }
        for (Endpoint endpoint : serverEndpoints) {
            endpointQueue.put(endpoint);
        }

        int endpointsFinished = 0;
        int totalEndpoints = endpointQueue.size();
        while (true) {
            Endpoint endpoint = endpointQueue.take(); // blocks

            dequeueCycles += 1;

            if (endpoint.exception().isPresent()) {
                throw endpoint.exception().get();
            }

            if (endpoint.remaining() == 0) {
                endpointsFinished += 1;
                if (endpointsFinished == totalEndpoints) {
                    break;
                }
            } else {

                if (endpoint instanceof WriterEndpoint) {
                    WriterEndpoint writer = (WriterEndpoint) endpoint;

                    if (!writer.buffer.hasRemaining()) {
                        TestUtil.nextBytes(writer.random, writer.buffer.array());
                        writer.buffer.position(0);
                        writer.buffer.limit(Math.min(writer.buffer.capacity(), writer.remaining));
                    }
                    writer.socketGroup.external.write(
                            writer.buffer, 1, TimeUnit.DAYS, null, new CompletionHandler<Integer, Object>() {
                                @Override
                                public void completed(Integer c, Object attach) {
                                    assertTrue(c > 0);
                                    writer.remaining -= c;
                                    try {
                                        endpointQueue.put(writer);
                                    } catch (InterruptedException e) {
                                        throw new RuntimeException(e);
                                    }
                                    completedWrites.increment();
                                }

                                @Override
                                public void failed(Throwable e, Object attach) {
                                    writer.exception = Optional.of(e);
                                    try {
                                        endpointQueue.put(writer);
                                    } catch (InterruptedException ex) {
                                        throw new RuntimeException(ex);
                                    }
                                    failedWrites.increment();
                                }
                            });
                } else if (endpoint instanceof ReaderEndpoint) {
                    ReaderEndpoint reader = (ReaderEndpoint) endpoint;
                    reader.buffer.clear();
                    reader.socketGroup.external.read(
                            reader.buffer, 1, TimeUnit.DAYS, null, new CompletionHandler<Integer, Object>() {
                                @Override
                                public void completed(Integer c, Object attach) {
                                    assertTrue(c > 0);
                                    reader.digest.update(reader.buffer.array(), 0, c);
                                    reader.remaining -= c;
                                    try {
                                        endpointQueue.put(reader);
                                    } catch (InterruptedException e) {
                                        throw new RuntimeException(e);
                                    }
                                    completedReads.increment();
                                }

                                @Override
                                public void failed(Throwable e, Object attach) {
                                    reader.exception = Optional.of(e);
                                    try {
                                        endpointQueue.put(reader);
                                    } catch (InterruptedException ex) {
                                        throw new RuntimeException(ex);
                                    }
                                    failedReads.increment();
                                }
                            });
                } else {
                    throw new IllegalStateException();
                }
            }
        }
        for (AsyncSocketPair socketPair : socketPairs) {
            socketPair.client.external.close();
            socketPair.server.external.close();
            SocketPairFactory.checkDeallocation(socketPair);
        }
        for (ReaderEndpoint reader : serverEndpoints) {
            assertArrayEquals(reader.digest.digest(), dataHash);
        }
        return new Report(
                dequeueCycles,
                completedReads.longValue(),
                failedReads.longValue(),
                completedWrites.longValue(),
                failedWrites.longValue());
    }
}
