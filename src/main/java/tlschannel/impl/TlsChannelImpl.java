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

	private static class EngineLoopResult {
		public final int bytes;
		public final HandshakeStatus lastHandshakeStatus;
		public final boolean wasClosed;

		public EngineLoopResult(int bytes, HandshakeStatus lastHandshakeStatus, boolean wasClosed) {
			this.bytes = bytes;
			this.lastHandshakeStatus = lastHandshakeStatus;
			this.wasClosed = wasClosed;
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
	private ByteBuffer inEncrypted;
	private final Consumer<SSLSession> initSessionCallback;

	private final boolean runTasks;
	private final BufferAllocator encryptedBufferAllocator;
	private final BufferAllocator plainBufferAllocator;
	private final boolean waitForCloseConfirmation;

	// @formatter:off
	public TlsChannelImpl(
			ReadableByteChannel readChannel, 
			WritableByteChannel writeChannel, 
			SSLEngine engine,
			Optional<ByteBuffer> inEncrypted, 
			Consumer<SSLSession> initSessionCallback, 
			boolean runTasks,
			BufferAllocator plainBufferAllocator,
			BufferAllocator encryptedBufferAllocator,
			boolean waitForCloseConfirmation) {
	// @formatter:on
		this.readChannel = readChannel;
		this.writeChannel = writeChannel;
		this.engine = engine;
		this.inEncrypted = inEncrypted.orElseGet(() -> encryptedBufferAllocator.allocate(buffersInitialSize));
		this.initSessionCallback = initSessionCallback;
		this.runTasks = runTasks;
		this.plainBufferAllocator = plainBufferAllocator;
		this.encryptedBufferAllocator = encryptedBufferAllocator;
		this.waitForCloseConfirmation = waitForCloseConfirmation;
		inPlain = plainBufferAllocator.allocate(buffersInitialSize);
		outEncrypted = encryptedBufferAllocator.allocate(buffersInitialSize);
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
	private ByteBuffer inPlain;

	// contains data encrypted to send to the network
	private ByteBuffer outEncrypted;

	// handshake wrap() method calls need a buffer to read from, even when they
	// actually do not read anything
	private final ByteBufferSet dummyOut = new ByteBufferSet(new ByteBuffer[] {});

	public Consumer<SSLSession> getSessionInitCallback() {
		return initSessionCallback;
	}

	public BufferAllocator getPlainBufferAllocator() {
		return plainBufferAllocator;
	}

	public BufferAllocator getEncryptedBufferAllocator() {
		return encryptedBufferAllocator;
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
			int bytesToReturn = inPlain.position();
			while (true) {
				if (bytesToReturn > 0) {
					if (inPlain.position() == 0) {
						return bytesToReturn;
					} else {
						return transferPendingPlain(dest);
					}
				}
				if (shutdownReceived) {
					return -1;
				}
				Util.assertTrue(inPlain.position() == 0);
				switch (handshakeStatus) {
				case NEED_UNWRAP:
				case NEED_WRAP:
					bytesToReturn = handshake(Optional.of(dest), Optional.of(handshakeStatus));
					handshakeStatus = NOT_HANDSHAKING;
					break;
				case NOT_HANDSHAKING:
				case FINISHED:
					EngineLoopResult res = readLoop(Optional.of(dest), NOT_HANDSHAKING /* statusCondition */,
							false /* closing */);
					if (res.wasClosed) {
						return -1;
					}
					bytesToReturn = res.bytes;
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
		inPlain.flip(); // will read
		int bytes = dstBuffers.putRemaining(inPlain);
		inPlain.compact(); // will write
		Util.zeroRemaining(inPlain);
		return bytes;
	}

	private EngineLoopResult unwrapLoop(Optional<ByteBufferSet> dest, HandshakeStatus statusCondition, boolean closing)
			throws SSLException {
		ByteBufferSet effDest = dest.orElseGet(() -> new ByteBufferSet(inPlain));
		while (true) {
			Util.assertTrue(inPlain.position() == 0);
			SSLEngineResult result = callEngineUnwrap(effDest);
			/*
			 * Note that data can be returned even in case of overflow, in that
			 * case, just return the data.
			 */
			if (result.bytesProduced() > 0 || result.getStatus() == Status.BUFFER_UNDERFLOW
					|| !closing && result.getStatus() == Status.CLOSED
					|| result.getHandshakeStatus() != statusCondition) {
				boolean wasClosed = result.getStatus() == Status.CLOSED;
				if (wasClosed) {
					shutdownReceived = true;
				}
				return new EngineLoopResult(result.bytesProduced(), result.getHandshakeStatus(), wasClosed);
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
					enlargeInPlain();
				}
				// inPlain changed, re-create the wrapper
				effDest = new ByteBufferSet(inPlain);
			}
		}
	}

	private SSLEngineResult callEngineUnwrap(ByteBufferSet dest) throws SSLException {
		try {
			SSLEngineResult result = engine.unwrap(inEncrypted, dest.array, dest.offset, dest.length);
			if (logger.isTraceEnabled()) {
				logger.trace("engine.unwrap() result [{}]. Engine status: {}; inEncrypted {}; inPlain: {}",
						Util.resultToString(result), result.getHandshakeStatus(), inEncrypted, dest);
			}
			return result;
		} catch (SSLException e) {
			// something bad was received from the network, we cannot
			// continue
			invalid = true;
			throw e;
		}
	}

	private int readFromNetwork() throws IOException, EofException {
		try {
			return readFromNetwork(readChannel, inEncrypted);
		} catch (NeedsReadException e) {
			throw e;
		} catch (IOException e) {
			// after a failed read, buffers can be in any state, close
			invalid = true;
			throw e;
		}
	}

	public static int readFromNetwork(ReadableByteChannel readChannel, ByteBuffer buffer)
			throws IOException, EofException {
		Util.assertTrue(buffer.hasRemaining());
		logger.trace("Reading from network");
		int res = readChannel.read(buffer); // IO block
		logger.trace("Read from network; response: {}, buffer: {}", res, buffer);
		if (res == -1) {
			throw new EofException();
		}
		if (res == 0) {
			throw new NeedsReadException();
		}
		return res;
	}

	// write

	public long write(ByteBufferSet source) throws IOException {
		long bytesToConsume = source.remaining();
		if (bytesToConsume == 0)
			return 0;
		handshake();
		long bytesConsumed = 0;
		writeLock.lock();
		try {
			if (invalid || shutdownSent) {
				throw new ClosedChannelException();
			}
			while (true) {
				flipAndWriteToNetwork();
				if (bytesConsumed == bytesToConsume)
					return bytesToConsume;
				EngineLoopResult res = wrapLoop(source);
				Util.assertTrue(!res.wasClosed);
				bytesConsumed += res.bytes;
			}
		} finally {
			writeLock.unlock();
		}
	}

	private EngineLoopResult wrapLoop(ByteBufferSet source) throws SSLException {
		while (true) {
			SSLEngineResult result = callEngineWrap(source);
			switch (result.getStatus()) {
			case OK:
				return new EngineLoopResult(result.bytesConsumed(), result.getHandshakeStatus(), false /* wasClosed */);
			case CLOSED:
				return new EngineLoopResult(result.bytesConsumed(), result.getHandshakeStatus(), true /* wasClosed */);
			case BUFFER_OVERFLOW:
				Util.assertTrue(result.bytesConsumed() == 0);
				enlargeOutEncrypted();
				break;
			case BUFFER_UNDERFLOW:
				throw new IllegalStateException();
			}
		}
	}

	private SSLEngineResult callEngineWrap(ByteBufferSet source) throws SSLException {
		try {
			SSLEngineResult result = engine.wrap(source.array, source.offset, source.length, outEncrypted);
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

	private void enlargeOutEncrypted() {
		outEncrypted = Util.enlarge(encryptedBufferAllocator, outEncrypted, "outEncrypted", maxTlsPacketSize,
				false /* zero */);
	}

	private void enlargeInPlain() {
		inPlain = Util.enlarge(plainBufferAllocator, inPlain, "inPlain", maxTlsPacketSize, true /* zero */);
	}

	private void enlargeInEncrypted() {
		inEncrypted = Util.enlarge(encryptedBufferAllocator, inEncrypted, "inEncrypted", maxTlsPacketSize,
				false /* zero */);
	}

	private void ensureInPlainCapacity(int newCapacity) {
		if (inPlain.capacity() < newCapacity) {
			logger.trace("inPlain buffer too small, increasing from {} to {}", inPlain.capacity(), newCapacity);
			inPlain = Util.resize(plainBufferAllocator, inPlain, newCapacity, true /* zero */);
		}
	}

	private void flipAndWriteToNetwork() throws IOException {
		if (outEncrypted.position() == 0) {
			return;
		}
		outEncrypted.flip();
		try {
			writeToNetwork();
		} finally {
			outEncrypted.compact();
		}
	}

	private void writeToNetwork() throws IOException {
		while (outEncrypted.hasRemaining()) {
			logger.trace("Writing to network: {}", outEncrypted);
			int c;
			try {
				c = writeChannel.write(outEncrypted);
			} catch (IOException e) {
				// after a failed write, buffers can be in any state, close
				invalid = true;
				throw e;
			}
			if (c == 0) {
				/*
				 * If no bytes were written, it means that the socket is
				 * non-blocking and needs more buffer space, so stop the loop
				 */
				// return bytesWritten;
				throw new NeedsWriteException();
			}
			// blocking SocketChannels can write less than all the bytes
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
				Util.assertTrue(inPlain.position() == 0);
				flipAndWriteToNetwork(); // IO block
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
		Util.assertTrue(inPlain.position() == 0);
		HandshakeStatus status = handshakeStatus.orElseGet(() -> engine.getHandshakeStatus());
		while (true) {
			switch (status) {
			case NEED_WRAP:
				Util.assertTrue(outEncrypted.position() == 0);
				EngineLoopResult wrapResult = wrapLoop(dummyOut);
				status = wrapResult.lastHandshakeStatus;
				flipAndWriteToNetwork(); // IO block
				break;
			case NEED_UNWRAP:
				EngineLoopResult res = readLoop(dest, NEED_UNWRAP /* statusCondition */, false /* closing */);
				status = res.lastHandshakeStatus;
				if (res.bytes > 0)
					return res.bytes;
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

	private EngineLoopResult readLoop(Optional<ByteBufferSet> dest, HandshakeStatus statusCondition, boolean closing)
			throws IOException, EofException {
		while (true) {
			Util.assertTrue(inPlain.position() == 0);
			inEncrypted.flip();
			try {
				EngineLoopResult res = unwrapLoop(dest, statusCondition, closing);
				if (res.bytes > 0 || res.lastHandshakeStatus != statusCondition || !closing && res.wasClosed) {
					return res;
				}
			} finally {
				inEncrypted.compact();
			}
			if (!inEncrypted.hasRemaining()) {
				enlargeInEncrypted();
			}
			readFromNetwork(); // IO block
		}
	}

	public void close() throws IOException {
		tryShutdown();
		writeChannel.close();
		readChannel.close();
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
					flipAndWriteToNetwork(); // IO block
					engine.closeOutbound();
					wrapLoop(dummyOut);
					flipAndWriteToNetwork(); // IO block
					/*
					 * If this side is the first to send close_notify, then,
					 * inbound is not done and false should be returned (so the
					 * client waits for the response. If this side is the
					 * second, then inbound was already done, and we can return
					 * true.
					 */
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
						readLoop(Optional.empty(), NEED_UNWRAP /* statusCondition */, true /* closing */);
						Util.assertTrue(shutdownReceived);
					} catch (EofException e) {
						throw new ClosedChannelException();
					}
				}
				return true;
			} finally {
				writeLock.unlock();
			}
		} finally {
			readLock.unlock();
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
