package tlschannel.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tlschannel.NeedsReadException;
import tlschannel.NeedsTaskException;
import tlschannel.NeedsWriteException;
import tlschannel.TlsChannel;
import tlschannel.impl.ByteBufferSet;
import tlschannel.util.Util;

import java.io.IOException;
import java.nio.channels.*;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * This class encapsulates the infrastructure for running {@link AsynchronousTlsChannel}s. Each instance of this class
 * is a singleton-like object that manages a thread pool that makes it possible to run a group of asynchronous channels.
 */
public class AsynchronousTlsChannelGroup {

    private static final Logger logger = LoggerFactory.getLogger(AsynchronousTlsChannelGroup.class);

    /**
     * The main executor of the group has a queue, whose size is a multiple of the number of CPUs.
     */
    private static final int queueLengthMultiplier = 8;

    private static AtomicInteger globalGroupCount = new AtomicInteger();

    class RegisteredSocket {

        final TlsChannel tlsChannel;
        final SocketChannel socketChannel;

        /**
         * Used to wait until the channel is effectively in the selector (which happens asynchronously to the initial
         * registration.
         */
        final CountDownLatch registered = new CountDownLatch(1);

        SelectionKey key;

        /**
         * Protects {@link #readOperation} reference and instance.
         */
        final Lock readLock = new ReentrantLock();

        /**
         * Protects {@link #writeOperation} reference and instance.
         */
        final Lock writeLock = new ReentrantLock();

        /**
         * Current read operation, in not null
         */
        ReadOperation readOperation;

        /**
         * Current write operation, if not null
         */
        WriteOperation writeOperation;

        /**
         * Bitwise union of pending operation to be registered in the selector
         */
        final AtomicInteger pendingOps = new AtomicInteger();

        RegisteredSocket(TlsChannel tlsChannel, SocketChannel socketChannel) throws ClosedChannelException {
            this.tlsChannel = tlsChannel;
            this.socketChannel = socketChannel;
        }

        public void close() {
            doCancelRead(this, null);
            doCancelWrite(this, null);
            key.cancel();
            currentRegistrations.getAndDecrement();
            /*
             * Actual de-registration from the selector will happen asynchronously.
             */
            selector.wakeup();
        }
    }

    private static abstract class Operation {
        final ByteBufferSet bufferSet;
        final LongConsumer onSuccess;
        final Consumer<Throwable> onFailure;
        Future<?> timeoutFuture;

        Operation(ByteBufferSet bufferSet, LongConsumer onSuccess, Consumer<Throwable> onFailure) {
            this.bufferSet = bufferSet;
            this.onSuccess = onSuccess;
            this.onFailure = onFailure;
        }
    }

    static final class ReadOperation extends Operation {
        ReadOperation(ByteBufferSet bufferSet, LongConsumer onSuccess, Consumer<Throwable> onFailure) {
            super(bufferSet, onSuccess, onFailure);
        }
    }

    static final class WriteOperation extends Operation {

        /**
         * Because a write operation can flag a block (needs read/write) even after the source buffer was read from,
         * we need to accumulate consumed bytes.
         */
        long consumesBytes = 0;

        WriteOperation(ByteBufferSet bufferSet, LongConsumer onSuccess, Consumer<Throwable> onFailure) {
            super(bufferSet, onSuccess, onFailure);
        }
    }

    private final int id = globalGroupCount.getAndIncrement();

    /**
     * With the intention of being spacer with warnings, use this flag to ensure that we only log the warning about
     * needed task once.
     */
    private final AtomicBoolean loggedTaskWarning = new AtomicBoolean();

    private final Selector selector = Selector.open();

    private final ExecutorService executor;

    private final ScheduledThreadPoolExecutor timeoutExecutor = new ScheduledThreadPoolExecutor(1, runnable ->
            new Thread(runnable, String.format("async-channel-group-%d-timeout-thread", id))
    );

    private final Thread selectorThread = new Thread(this::loop, String.format("async-channel-group-%d-selector", id));

    private final ConcurrentLinkedQueue<RegisteredSocket> pendingRegistrations = new ConcurrentLinkedQueue<>();

    private enum Shutdown {
        No, Wait, Immediate
    }

    private volatile Shutdown shutdown = Shutdown.No;

    private LongAdder selectionCount = new LongAdder();

    private LongAdder startedReads = new LongAdder();
    private LongAdder startedWrites = new LongAdder();
    private LongAdder successfulReads = new LongAdder();
    private LongAdder successfulWrites = new LongAdder();
    private LongAdder failedReads = new LongAdder();
    private LongAdder failedWrites = new LongAdder();
    private LongAdder cancelledReads = new LongAdder();
    private LongAdder cancelledWrites = new LongAdder();

    // used for synchronization
    private AtomicInteger currentRegistrations = new AtomicInteger();

    private LongAdder currentReads = new LongAdder();
    private LongAdder currentWrites = new LongAdder();

    public AsynchronousTlsChannelGroup(int nThreads) throws IOException {
        timeoutExecutor.setRemoveOnCancelPolicy(true);
        this.executor = new ThreadPoolExecutor(
                nThreads, nThreads,
                0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(nThreads * queueLengthMultiplier),
                runnable -> new Thread(runnable, String.format("async-channel-group-%d-handler-executor", id)),
                new ThreadPoolExecutor.CallerRunsPolicy());
        selectorThread.start();
    }

    RegisteredSocket registerSocket(TlsChannel reader, SocketChannel socketChannel) throws ClosedChannelException {
        if (shutdown != Shutdown.No) {
            throw new ShutdownChannelGroupException();
        }
        RegisteredSocket socket = new RegisteredSocket(reader, socketChannel);
        currentRegistrations.getAndIncrement();
        pendingRegistrations.add(socket);
        selector.wakeup();
        return socket;
    }

    boolean doCancelRead(RegisteredSocket socket, ReadOperation op) {
        socket.readLock.lock();
        try {
            // a null op means cancel any operation
            if (op != null && socket.readOperation == op || op == null && socket.readOperation != null) {
                socket.readOperation = null;
                cancelledReads.increment();
                currentReads.decrement();
                return true;
            } else {
                return false;
            }
        } finally {
            socket.readLock.unlock();
        }
    }

    boolean doCancelWrite(RegisteredSocket socket, WriteOperation op) {
        socket.writeLock.lock();
        try {
            // a null op means cancel any operation
            if (op != null && socket.writeOperation == op || op == null && socket.writeOperation != null) {
                socket.writeOperation = null;
                cancelledWrites.increment();
                currentWrites.decrement();
                return true;
            } else {
                return false;
            }
        } finally {
            socket.writeLock.unlock();
        }
    }

    ReadOperation startRead(
            RegisteredSocket socket,
            ByteBufferSet buffer,
            long timeout, TimeUnit unit,
            LongConsumer onSuccess, Consumer<Throwable> onFailure)
            throws ReadPendingException {
        checkTerminated();
        Util.assertTrue(buffer.hasRemaining());
        waitForSocketRegistration(socket);
        ReadOperation op;
        socket.readLock.lock();
        try {
            if (socket.readOperation != null) {
                throw new ReadPendingException();
            }
            op = new ReadOperation(buffer, onSuccess, onFailure);
            /*
             * we do not try to outsmart the TLS state machine and register for both IO operations for each new socket
             * operation
             */
            socket.pendingOps.set(SelectionKey.OP_WRITE | SelectionKey.OP_READ);
            if (timeout != 0) {
                op.timeoutFuture = timeoutExecutor.schedule(() -> {
                    boolean success = doCancelRead(socket, op);
                    if (success) {
                        op.onFailure.accept(new InterruptedByTimeoutException());
                    }
                }, timeout, unit);
            }
            socket.readOperation = op;
        } finally {
            socket.readLock.unlock();
        }
        selector.wakeup();
        startedReads.increment();
        currentReads.increment();
        return op;
    }

    WriteOperation startWrite(
            RegisteredSocket socket,
            ByteBufferSet buffer,
            long timeout, TimeUnit unit,
            LongConsumer onSuccess, Consumer<Throwable> onFailure)
            throws WritePendingException {
        checkTerminated();
        Util.assertTrue(buffer.hasRemaining());
        waitForSocketRegistration(socket);
        WriteOperation op;
        socket.writeLock.lock();
        try {
            if (socket.writeOperation != null) {
                throw new WritePendingException();
            }
            op = new WriteOperation(buffer, onSuccess, onFailure);
            /*
             * we do not try to outsmart the TLS state machine and register for both IO operations for each new socket
             * operation
             */
            socket.pendingOps.set(SelectionKey.OP_WRITE | SelectionKey.OP_READ);
            if (timeout != 0) {
                op.timeoutFuture = timeoutExecutor.schedule(() -> {
                    boolean success = doCancelWrite(socket, op);
                    if (success) {
                        op.onFailure.accept(new InterruptedByTimeoutException());
                    }
                }, timeout, unit);
            }
            socket.writeOperation = op;
        } finally {
            socket.writeLock.unlock();
        }
        selector.wakeup();
        startedWrites.increment();
        currentWrites.increment();
        return op;
    }

    private void checkTerminated() {
        if (isTerminated()) {
            throw new ShutdownChannelGroupException();
        }
    }

    private void waitForSocketRegistration(RegisteredSocket socket) {
        try {
            socket.registered.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private void loop() {
        try {
            while (shutdown == Shutdown.No || shutdown == Shutdown.Wait && currentRegistrations.intValue() > 0) {
                int c = selector.select(); // block
                selectionCount.increment();
                // avoid unnecessary creation of iterator object
                if (c > 0) {
                    Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                    while (it.hasNext()) {
                        SelectionKey key = it.next();
                        it.remove();
                        try {
                            key.interestOps(0);
                        } catch (CancelledKeyException e) {
                            // can happen when channels are closed with pending operations
                            continue;
                        }
                        RegisteredSocket socket = (RegisteredSocket) key.attachment();
                        processRead(socket);
                        processWrite(socket);
                    }
                }
                registerPendingSockets();
                processPendingInterests();
            }
        } catch (Throwable e) {
            logger.error("error in selector loop", e);
        } finally {
            executor.shutdown();
            // use shutdownNow to stop delayed tasks
            timeoutExecutor.shutdownNow();
            if (shutdown == Shutdown.Immediate) {
                for (SelectionKey key : selector.keys()) {
                    RegisteredSocket socket = (RegisteredSocket) key.attachment();
                    socket.close();
                }
            }
            try {
                selector.close();
            } catch (IOException e) {
                logger.warn("error closing selector: {}", e.getMessage());
            }
        }
    }

    private void processPendingInterests() {
        for (SelectionKey key : selector.keys()) {
            RegisteredSocket socket = (RegisteredSocket) key.attachment();
            int pending = socket.pendingOps.getAndSet(0);
            if (pending != 0) {
                key.interestOps(key.interestOps() | pending);
            }
        }
    }

    private void processWrite(RegisteredSocket socket) {
        socket.writeLock.lock();
        try {
            WriteOperation op = socket.writeOperation;
            if (op != null) {
                executor.execute(() -> {
                    try {
                        doWrite(socket, op);
                    } catch (Throwable e) {
                        logger.error("error in operation", e);
                    }
                });
            }
        } finally {
            socket.writeLock.unlock();
        }
    }

    private void processRead(RegisteredSocket socket) {
        socket.readLock.lock();
        try {
            ReadOperation op = socket.readOperation;
            if (op != null) {
                executor.execute(() -> {
                    try {
                        doRead(socket, op);
                    } catch (Throwable e) {
                        logger.error("error in operation", e);
                    }
                });
            }
        } finally {
            socket.readLock.unlock();
        }
    }

    private void doWrite(RegisteredSocket socket, WriteOperation op) {
        socket.writeLock.lock();
        try {
            if (socket.writeOperation != op) {
                return;
            }
            try {
                long before = op.bufferSet.remaining();
                try {
                    writeHandlingTasks(socket, op);
                } finally {
                    long c = before - op.bufferSet.remaining();
                    Util.assertTrue(c >= 0);
                    op.consumesBytes += c;
                }
                socket.writeOperation = null;
                if (op.timeoutFuture != null) {
                    op.timeoutFuture.cancel(false);
                }
                op.onSuccess.accept(op.consumesBytes);
                successfulWrites.increment();
                currentWrites.decrement();
            } catch (NeedsReadException e) {
                socket.pendingOps.accumulateAndGet(SelectionKey.OP_READ, (a, b) -> a | b);
                selector.wakeup();
            } catch (NeedsWriteException e) {
                socket.pendingOps.accumulateAndGet(SelectionKey.OP_WRITE, (a, b) -> a | b);
                selector.wakeup();
            } catch (IOException e) {
                if (socket.writeOperation == op) {
                    socket.writeOperation = null;
                }
                if (op.timeoutFuture != null) {
                    op.timeoutFuture.cancel(false);
                }
                op.onFailure.accept(e);
                failedWrites.increment();
                currentWrites.decrement();
            }
        } finally {
            socket.writeLock.unlock();
        }
    }

    /**
     * Intended use of the channel group is with sockets that run tasks internally, but out of tolerance,
     * run tasks in thread in case the socket does not.
     */
    private void writeHandlingTasks(RegisteredSocket socket, WriteOperation op) throws IOException {
        while (true) {
            try {
                socket.tlsChannel.write(op.bufferSet.array, op.bufferSet.offset, op.bufferSet.length);
                return;
            } catch (NeedsTaskException e) {
                warnAboutNeedTask();
                e.getTask().run();
            }
        }
    }

    private void warnAboutNeedTask() {
        if (!loggedTaskWarning.getAndSet(true)) {
            logger.warn(
                    "caught {}; channels used in asynchronous groups should run tasks themselves; " +
                    "although task is being dealt with anyway, consider configuring channels properly",
                    NeedsTaskException.class.getName());
        }
    }

    private void doRead(RegisteredSocket socket, ReadOperation op) {
        socket.readLock.lock();
        try {
            if (socket.readOperation != op) {
                return;
            }
            try {
                Util.assertTrue(op.bufferSet.hasRemaining());
                long c = readHandlingTasks(socket, op);
                Util.assertTrue(c > 0 || c == -1);
                socket.readOperation = null;
                if (op.timeoutFuture != null) {
                    op.timeoutFuture.cancel(false);
                }
                op.onSuccess.accept(c);
                successfulReads.increment();
                currentReads.decrement();
            } catch (NeedsReadException e) {
                socket.pendingOps.accumulateAndGet(SelectionKey.OP_READ, (a, b) -> a | b);
                selector.wakeup();
            } catch (NeedsWriteException e) {
                socket.pendingOps.accumulateAndGet(SelectionKey.OP_WRITE, (a, b) -> a | b);
                selector.wakeup();
            } catch (IOException e) {
                if (socket.readOperation == op) {
                    socket.readOperation = null;
                }
                if (op.timeoutFuture != null) {
                    op.timeoutFuture.cancel(false);
                }
                op.onFailure.accept(e);
                failedReads.increment();
                currentReads.decrement();
            }
        } finally {
            socket.readLock.unlock();
        }
    }

    /**
     * @see #writeHandlingTasks
     */
    private long readHandlingTasks(RegisteredSocket socket, ReadOperation op) throws IOException {
        while (true) {
            try {
                return socket.tlsChannel.read(op.bufferSet.array, op.bufferSet.offset, op.bufferSet.length);
            } catch (NeedsTaskException e) {
                warnAboutNeedTask();
                e.getTask().run();
            }
        }
    }

    private void registerPendingSockets() throws ClosedChannelException {
        RegisteredSocket socket;
        while ((socket = pendingRegistrations.poll()) != null) {
            socket.key = socket.socketChannel.register(selector, 0, socket);
            logger.debug("registered key: {}", socket.key);
            socket.registered.countDown();
        }
    }

    public boolean isShutdown() {
        return shutdown != Shutdown.No;
    }

    public void shutdown() {
        shutdown = Shutdown.Wait;
        selector.wakeup();
    }

    public void shutdownNow() {
        shutdown = Shutdown.Immediate;
        selector.wakeup();
    }

    public boolean isTerminated() {
        return executor.isTerminated();
    }

    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return executor.awaitTermination(timeout, unit);
    }

    public long getSelectionCount() {
        return selectionCount.longValue();
    }

    public long getStartedReadCount() {
        return startedReads.longValue();
    }

    public long getStartedWriteCount() {
        return startedWrites.longValue();
    }

    public long getSuccessfulReadCount() {
        return successfulReads.longValue();
    }

    public long getSuccessfulWriteCount() {
        return successfulWrites.longValue();
    }

    public long getFailedReadCount() {
        return failedReads.longValue();
    }

    public long getFailedWriteCount() {
        return failedWrites.longValue();
    }

    public long getCancelledReadCount() {
        return cancelledReads.longValue();
    }

    public long getCancelledWriteCount() {
        return cancelledWrites.longValue();
    }

    public long getCurrentReadCount() {
        return currentReads.longValue();
    }

    public long getCurrentWriteCount() {
        return currentWrites.longValue();
    }

    public long getCurrentRegistrationCount() {
        return currentRegistrations.longValue();
    }

}
