package tlschannel.impl;

import javax.net.ssl.SSLEngineResult.HandshakeStatus;

import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLEngine;

import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;

import static javax.net.ssl.SSLEngineResult.HandshakeStatus.*;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tlschannel.BufferAllocator;
import tlschannel.NeedsReadException;
import tlschannel.NeedsTaskException;
import tlschannel.NeedsWriteException;
import tlschannel.WouldBlockException;
import tlschannel.util.Util;

import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

public class TlsChannelImpl implements ByteChannel {

	private static final Logger logger = LoggerFactory.getLogger(TlsChannelImpl.class);

	public static final int buffersInitialSize = 4096;

	/**
	 * Official TLS max data size is 2^14 = 16k. Use 1024 more to account for
	 * the overhead
	 */
	public final static int maxTlsPacketSize = 17 * 1024;

	private static class UnwrapResult {
		public final int bytesProduced;
		public final HandshakeStatus lastHandshakeStatus;
		public final boolean wasClosed;

		public UnwrapResult(int bytesProduced, HandshakeStatus lastHandshakeStatus, boolean wasClosed) {
			this.bytesProduced = bytesProduced;
			this.lastHandshakeStatus = lastHandshakeStatus;
			this.wasClosed = wasClosed;
		}
	}

	private static class WrapResult {
		public final int bytesConsumed;
		public final HandshakeStatus lastHandshakeStatus;

		public WrapResult(int bytesConsumed, HandshakeStatus lastHandshakeStatus) {
			this.bytesConsumed = bytesConsumed;
			this.lastHandshakeStatus = lastHandshakeStatus;
		}
	}
	
	/**
	 * Used to signal EOF conditions from the underlying channel
	 */
	public static class EofException extends Exception {

		/**
		 * For efficiency, override this method to do nothing.
		 */
		@Override
		public Throwable fillInStackTrace() {
			return this;
		}

	}

	private final ReadableByteChannel readChannel;
	private final WritableByteChannel writeChannel;
	private final SSLEngine engine;
	private BufferHolder inEncrypted;
	private final Consumer<SSLSession> initSessionCallback;

	private final boolean runTasks;
	private final BufferAllocator encryptedBufAllocator;
	private final BufferAllocator plainBufAllocator;
	private final boolean waitForCloseConfirmation;

	// @formatter:off
	public TlsChannelImpl(
			ReadableByteChannel readChannel, 
			WritableByteChannel writeChannel, 
			SSLEngine engine,
			Optional<BufferHolder> inEncrypted,
			Consumer<SSLSession> initSessionCallback, 
			boolean runTasks,
			BufferAllocator plainBufAllocator,
			BufferAllocator encryptedBufAllocator,
			boolean waitForCloseConfirmation) {
	// @formatter:on
		this.readChannel = readChannel;
		this.writeChannel = writeChannel;
		this.engine = engine;
		this.inEncrypted = inEncrypted.orElseGet(() ->
                new BufferHolder("inEncrypted", Optional.empty(), encryptedBufAllocator, buffersInitialSize, maxTlsPacketSize, false /* plainData */));
		this.initSessionCallback = initSessionCallback;
		this.runTasks = runTasks;
		this.plainBufAllocator = plainBufAllocator;
		this.encryptedBufAllocator = encryptedBufAllocator;
		this.waitForCloseConfirmation = waitForCloseConfirmation;
		inPlain = new BufferHolder("inPlain", Optional.empty(), plainBufAllocator, buffersInitialSize, maxTlsPacketSize, true /* plainData */);
		outEncrypted = new BufferHolder("outEncrypted", Optional.empty(), encryptedBufAllocator, buffersInitialSize, maxTlsPacketSize, false /* plainData */);
	}

	private final Lock initLock = new ReentrantLock();
	private final Lock readLock = new ReentrantLock();
	private final Lock writeLock = new ReentrantLock();

	private volatile boolean negotiated = false;

	/**
	 * Whether a IOException was received from the underlying channel or from
	 * the {@link SSLEngine}.
	 */
	private volatile boolean invalid = false;

	/**
	 * Whether a close_notify was already sent.
	 */
	private volatile boolean shutdownSent = false;

	/**
	 * Whether a close_notify was already received.
	 */
	private volatile boolean shutdownReceived = false;

	// decrypted data from inEncrypted
	private BufferHolder inPlain;

	// contains data encrypted to send to the underlying channel
	private BufferHolder outEncrypted;

	// handshake wrap() method calls need a buffer to read from, even when they
	// actually do not read anything
	private final ByteBufferSet dummyOut = new ByteBufferSet(new ByteBuffer[] {});

	public Consumer<SSLSession> getSessionInitCallback() {
		return initSessionCallback;
	}

	public BufferAllocator getPlainBufferAllocator() {
		return plainBufAllocator;
	}

	public BufferAllocator getEncryptedBufferAllocator() {
		return encryptedBufAllocator;
	}

	// read

	public long read(ByteBufferSet dest) throws IOException, NeedsTaskException {
		checkReadBuffer(dest);
		if (!dest.hasRemaining())
			return 0;
		handshake();
		readLock.lock();
		try {
			if (invalid || shutdownSent) {
				throw new ClosedChannelException();
			}
			HandshakeStatus handshakeStatus = engine.getHandshakeStatus();
			int bytesToReturn = inPlain.buffer.position();
			while (true) {
				if (bytesToReturn > 0) {
					if (inPlain.buffer.position() == 0) {
						return bytesToReturn;
					} else {
						return transferPendingPlain(dest);
					}
				}
				if (shutdownReceived) {
					return -1;
				}
				Util.assertTrue(inPlain.buffer.position() == 0);
				switch (handshakeStatus) {
				case NEED_UNWRAP:
				case NEED_WRAP:
					bytesToReturn = handshake(Optional.of(dest), Optional.of(handshakeStatus));
					handshakeStatus = NOT_HANDSHAKING;
					break;
				case NOT_HANDSHAKING:
				case FINISHED:
					UnwrapResult res = readAndUnwrap(Optional.of(dest), NOT_HANDSHAKING /* statusCondition */,
							false /* closing */);
					if (res.wasClosed) {
						return -1;
					}
					bytesToReturn = res.bytesProduced;
					handshakeStatus = res.lastHandshakeStatus;
					break;
				case NEED_TASK:
					handleTask();
					handshakeStatus = engine.getHandshakeStatus();
					break;
				}
			}
		} catch (EofException e) {
			return -1;
		} finally {
			readLock.unlock();
		}
	}

	private void handleTask() throws NeedsTaskException {
		if (runTasks) {
			engine.getDelegatedTask().run();
		} else {
			throw new NeedsTaskException(engine.getDelegatedTask());
		}
	}

	private int transferPendingPlain(ByteBufferSet dstBuffers) {
		inPlain.buffer.flip(); // will read
		int bytes = dstBuffers.putRemaining(inPlain.buffer);
		inPlain.buffer.compact(); // will write
        inPlain.zeroRemaining();
		return bytes;
	}

	private UnwrapResult unwrapLoop(Optional<ByteBufferSet> dest, HandshakeStatus statusCondition, boolean closing)
			throws SSLException {
		ByteBufferSet effDest = dest.orElseGet(() -> new ByteBufferSet(inPlain.buffer));
		while (true) {
			Util.assertTrue(inPlain.buffer.position() == 0);
			SSLEngineResult result = callEngineUnwrap(effDest);
			/*
			 * Note that data can be returned even in case of overflow, in that
			 * case, just return the data.
			 */
			if (result.bytesProduced() > 0 || result.getStatus() == Status.BUFFER_UNDERFLOW
					|| !closing && result.getStatus() == Status.CLOSED
					|| result.getHandshakeStatus() != statusCondition) {
				boolean wasClosed = result.getStatus() == Status.CLOSED;
				return new UnwrapResult(result.bytesProduced(), result.getHandshakeStatus(), wasClosed);
			}
			if (result.getStatus() == Status.BUFFER_OVERFLOW) {
				if (dest.isPresent() && effDest == dest.get()) {
					/*
					 * The client-supplier buffer is not big enough. Use the
					 * internal inPlain buffer, also ensure that it is bigger
					 * than the too-small supplied one.
					 */
					ensureInPlainCapacity(Math.min(((int) dest.get().remaining()) * 2, maxTlsPacketSize));
				} else {
                    inPlain.enlarge();
                }
				// inPlain changed, re-create the wrapper
				effDest = new ByteBufferSet(inPlain.buffer);
			}
		}
	}

	private SSLEngineResult callEngineUnwrap(ByteBufferSet dest) throws SSLException {
		try {
			SSLEngineResult result = engine.unwrap(inEncrypted.buffer, dest.array, dest.offset, dest.length);
			if (logger.isTraceEnabled()) {
				logger.trace("engine.unwrap() result [{}]. Engine status: {}; inEncrypted {}; inPlain: {}",
						Util.resultToString(result), result.getHandshakeStatus(), inEncrypted, dest);
			}
			return result;
		} catch (SSLException e) {
			// something bad was received from the underlying channel, we cannot
			// continue
			invalid = true;
			throw e;
		}
	}

	private int readFromChannel() throws IOException, EofException {
		try {
			return readFromChannel(readChannel, inEncrypted.buffer);
		} catch (WouldBlockException e) {
			throw e;
		} catch (IOException e) {
			invalid = true;
			throw e;
		}
	}

	public static int readFromChannel(ReadableByteChannel readChannel, ByteBuffer buffer)
			throws IOException, EofException {
		Util.assertTrue(buffer.hasRemaining());
		logger.trace("Reading from channel");
		int c = readChannel.read(buffer); // IO block
		logger.trace("Read from channel; response: {}, buffer: {}", c, buffer);
		if (c == -1) {
			throw new EofException();
		}
		if (c == 0) {
			throw new NeedsReadException();
		}
		return c;
	}

	// write

	public long write(ByteBufferSet source) throws IOException {
		long bytesToConsume = source.remaining();
		if (bytesToConsume == 0)
			return 0;
		handshake();
		writeLock.lock();
		try {
			if (invalid || shutdownSent) {
				throw new ClosedChannelException();
			}
			return wrapAndWrite(source);
		} finally {
			writeLock.unlock();
		}
	}

	private long wrapAndWrite(ByteBufferSet source) throws IOException {
		long bytesToConsume = source.remaining();
		long bytesConsumed = 0;
		while (true) {
			writeToChannel();
			if (bytesConsumed == bytesToConsume)
				return bytesToConsume;
			WrapResult res = wrapLoop(source);
			bytesConsumed += res.bytesConsumed;
		}
	}

	private WrapResult wrapLoop(ByteBufferSet source) throws SSLException {
		while (true) {
			SSLEngineResult result = callEngineWrap(source);
			switch (result.getStatus()) {
			case OK:
			case CLOSED:
				return new WrapResult(result.bytesConsumed(), result.getHandshakeStatus());
			case BUFFER_OVERFLOW:
				Util.assertTrue(result.bytesConsumed() == 0);
                outEncrypted.enlarge();
                break;
			case BUFFER_UNDERFLOW:
				throw new IllegalStateException();
			}
		}
	}

	private SSLEngineResult callEngineWrap(ByteBufferSet source) throws SSLException {
		try {
			SSLEngineResult result = engine.wrap(source.array, source.offset, source.length, outEncrypted.buffer);
			if (logger.isTraceEnabled()) {
				logger.trace("engine.wrap() result: [{}]; engine status: {}; srcBuffer: {}, outEncrypted: {}",
						Util.resultToString(result), result.getHandshakeStatus(), source, outEncrypted);
			}
			return result;
		} catch (SSLException e) {
			invalid = true;
			throw e;
		}
	}

    private void ensureInPlainCapacity(int newCapacity) {
		if (inPlain.buffer.capacity() < newCapacity) {
			logger.trace("inPlain buffer too small, increasing from {} to {}", inPlain.buffer.capacity(), newCapacity);
			inPlain.resize(newCapacity);
		}
	}

	private void writeToChannel() throws IOException {
		if (outEncrypted.buffer.position() == 0) {
			return;
		}
		outEncrypted.buffer.flip();
		try {
			try {
				writeToChannel(writeChannel, outEncrypted.buffer);
			} catch (WouldBlockException e) {
				throw e;
			} catch (IOException e) {
				invalid = true;
				throw e;
			}
		} finally {
			outEncrypted.buffer.compact();
		}
	}

	private static void writeToChannel(WritableByteChannel channel, ByteBuffer src) throws IOException {
		while (src.hasRemaining()) {
			logger.trace("Writing to channel: {}", src);
			int c = channel.write(src);
			if (c == 0) {
				/*
				 * If no bytesProduced were written, it means that the socket is
				 * non-blocking and needs more buffer space, so stop the loop
				 */
				throw new NeedsWriteException();
			}
			// blocking SocketChannels can write less than all the bytesProduced
			// just before an error the loop forces the exception
		}
	}

	// handshake and close

	/**
	 * Force new negotiation
	 */
	public void renegotiate() throws IOException {
		try {
			doHandshake(true /* force */);
		} catch (EofException e) {
			throw new ClosedChannelException();
		}
	}

	/**
	 * Do a negotiation if this connection is new and it hasn't been done
	 * already.
	 */
	public void handshake() throws IOException {
		try {
			doHandshake(false /* force */);
		} catch (EofException e) {
			throw new ClosedChannelException();
		}
	}

	private void doHandshake(boolean force) throws IOException, EofException {
		if (!force && negotiated)
			return;
		initLock.lock();
		if (invalid || shutdownSent)
			throw new ClosedChannelException();
		try {
			if (force || !negotiated) {
				engine.beginHandshake();
				logger.trace("Called engine.beginHandshake()");
				handshake(Optional.empty(), Optional.empty());
				// call client code
				initSessionCallback.accept(engine.getSession());
				negotiated = true;
			}
		} finally {
			initLock.unlock();
		}
	}

	private int handshake(Optional<ByteBufferSet> dest, Optional<HandshakeStatus> handshakeStatus)
			throws IOException, EofException {
		readLock.lock();
		try {
			writeLock.lock();
			try {
				Util.assertTrue(inPlain.buffer.position() == 0);
				writeToChannel(); // IO block
				return handshakeLoop(dest, handshakeStatus);
			} finally {
				writeLock.unlock();
			}
		} finally {
			readLock.unlock();
		}
	}

	private int handshakeLoop(Optional<ByteBufferSet> dest, Optional<HandshakeStatus> handshakeStatus)
			throws IOException, EofException {
		Util.assertTrue(inPlain.buffer.position() == 0);
		HandshakeStatus status = handshakeStatus.orElseGet(() -> engine.getHandshakeStatus());
		while (true) {
			switch (status) {
			case NEED_WRAP:
				Util.assertTrue(outEncrypted.buffer.position() == 0);
				WrapResult wrapResult = wrapLoop(dummyOut);
				status = wrapResult.lastHandshakeStatus;
				writeToChannel(); // IO block
				break;
			case NEED_UNWRAP:
				UnwrapResult res = readAndUnwrap(dest, NEED_UNWRAP /* statusCondition */, false /* closing */);
				status = res.lastHandshakeStatus;
				if (res.bytesProduced > 0)
					return res.bytesProduced;
				break;
			case NOT_HANDSHAKING:
				/*
				 * This should not really happen using SSLEngine, because
				 * handshaking ends with a FINISHED status. However, we accept
				 * this value to permit the use of a pass-through stub engine
				 * with no encryption.
				 */
				return 0;
			case NEED_TASK:
				handleTask();
				status = engine.getHandshakeStatus();
				break;
			case FINISHED:
				return 0;
			}
		}
	}

	private UnwrapResult readAndUnwrap(Optional<ByteBufferSet> dest, HandshakeStatus statusCondition, boolean closing)
			throws IOException, EofException {
		while (true) {
			Util.assertTrue(inPlain.buffer.position() == 0);
			inEncrypted.buffer.flip();
			try {
				UnwrapResult res = unwrapLoop(dest, statusCondition, closing);
				if (res.bytesProduced > 0 || res.lastHandshakeStatus != statusCondition || !closing && res.wasClosed) {
					if (res.wasClosed) {
						shutdownReceived = true;
					}
					return res;
				}
			} finally {
				inEncrypted.buffer.compact();
			}
			if (!inEncrypted.buffer.hasRemaining()) {
                inEncrypted.enlarge();
            }
			readFromChannel(); // IO block
		}
	}

	public void close() throws IOException {
		tryShutdown();
		writeChannel.close();
		readChannel.close();
		/*
		 * After closing the underlying channels, locks should be taken fast.
		 */
        readLock.lock();
		try {
		   writeLock.lock();
		   try {
		       freeBuffers();
           } finally {
               writeLock.unlock();
           }
        } finally {
		    readLock.unlock();
        }
	}

	private void tryShutdown() {
		if (!readLock.tryLock())
			return;
		try {
			if (!writeLock.tryLock())
				return;
			try {
				if (!shutdownSent) {
					try {
						boolean closed = shutdown();
						if (!closed && waitForCloseConfirmation) {
							shutdown();
						}
					} catch (Throwable e) {
						logger.debug("error doing TLS shutdown on close(), continuing: {}", e.getMessage());
					}
				}
			} finally {
				writeLock.unlock();
			}
		} finally {
			readLock.unlock();
		}
	}

	public boolean shutdown() throws IOException {
		readLock.lock();
		try {
			writeLock.lock();
			try {
				if (invalid) {
					throw new ClosedChannelException();
				}
				if (!shutdownSent) {
					shutdownSent = true;
					writeToChannel(); // IO block
					engine.closeOutbound();
					wrapLoop(dummyOut);
					writeToChannel(); // IO block
					/*
					 * If this side is the first to send close_notify, then,
					 * inbound is not done and false should be returned (so the
					 * client waits for the response. If this side is the
					 * second, then inbound was already done, and we can return
					 * true.
					 */
					if (shutdownReceived) {
						freeBuffers();
					}
					return shutdownReceived;
				}
				/*
				 * If we reach this point, then we just have to read the close
				 * notification from the client. Only try to do it if necessary,
				 * to make this method idempotent.
				 */
				if (!shutdownReceived) {
					try {
						// IO block
						readAndUnwrap(Optional.empty(), NEED_UNWRAP /* statusCondition */, true /* closing */);
						Util.assertTrue(shutdownReceived);
					} catch (EofException e) {
						throw new ClosedChannelException();
					}
				}
				freeBuffers();
				return true;
			} finally {
				writeLock.unlock();
			}
		} finally {
			readLock.unlock();
		}
	}

	private void freeBuffers() {
		if (inEncrypted != null) {
            inEncrypted.dispose();
            inEncrypted = null;
        }
		if (inPlain != null) {
			inPlain.dispose();
            inPlain = null;
        }
		if (outEncrypted != null) {
            outEncrypted.dispose();
            outEncrypted = null;
        }
	}

	public boolean isOpen() {
		return !invalid && writeChannel.isOpen() && readChannel.isOpen();
	}

	public static void checkReadBuffer(ByteBufferSet dest) {
		if (dest.isReadOnly())
			throw new IllegalArgumentException();
	}

	public SSLEngine engine() {
		return engine;
	}

	public boolean getRunTasks() {
		return runTasks;
	}

	@Override
	public int read(ByteBuffer dst) throws IOException {
		return (int) read(new ByteBufferSet(dst));
	}

	@Override
	public int write(ByteBuffer src) throws IOException {
		return (int) write(new ByteBufferSet(src));
	}

	public boolean shutdownReceived() {
		return shutdownReceived;
	}

	public boolean shutdownSent() {
		return shutdownSent;
	}

}
