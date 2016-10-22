package tlschannel;

import javax.net.ssl.SSLEngineResult.HandshakeStatus;

import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLEngine;
import java.nio.channels.ClosedChannelException;

import static javax.net.ssl.SSLEngineResult.HandshakeStatus.*;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

public class TlsSocketChannelImpl {

	private static final Logger logger = LoggerFactory.getLogger(TlsSocketChannelImpl.class);

	private static int buffersInitialSize = 2048;

	private static class LoopResult {
		public final int bytes;
		public final HandshakeStatus lastHandshakeStatus;

		public LoopResult(int bytes, HandshakeStatus lastHandshakeStatus) {
			this.bytes = bytes;
			this.lastHandshakeStatus = lastHandshakeStatus;
		}
	}
	
	private final ReadableByteChannel readChannel;
	private final WritableByteChannel writeChannel;
	private final SSLEngine engine;
	private ByteBuffer inEncrypted;
	private final Consumer<SSLSession> initSessionCallback;

	private final int tlsMaxDataSize;
	private final int tlsMaxRecordSize;
	private final boolean runTasks;

	public TlsSocketChannelImpl(ReadableByteChannel readChannel, WritableByteChannel writeChannel, SSLEngine engine,
			Optional<ByteBuffer> inEncrypted, Consumer<SSLSession> initSessionCallback, boolean runTasks) {
		this.readChannel = readChannel;
		this.writeChannel = writeChannel;
		this.engine = engine;
		this.inEncrypted = inEncrypted.orElseGet(() -> ByteBuffer.allocate(buffersInitialSize));
		this.initSessionCallback = initSessionCallback;
		// about 2^14
		this.tlsMaxDataSize = engine.getSession().getApplicationBufferSize();
		// about 2^14 + overhead
		this.tlsMaxRecordSize = engine.getSession().getPacketBufferSize();
		this.runTasks = runTasks;
	}

	private final Lock initLock = new ReentrantLock();
	private final Lock readLock = new ReentrantLock();
	private final Lock writeLock = new ReentrantLock();

	private volatile boolean initialHandshaked = false;
	private volatile boolean invalid = false;
	private boolean tlsClosePending = false;

	// decrypted data from inEncrypted
	private ByteBuffer inPlain = ByteBuffer.allocate(buffersInitialSize);

	// contains data encrypted to send to the network
	private ByteBuffer outEncrypted = ByteBuffer.allocate(buffersInitialSize);

	// handshake wrap() method calls need a buffer to read from, even when they
	// actually do not read anything
	private final ByteBufferSet dummyOut = new ByteBufferSet(new ByteBuffer[] {});

	// read

	public long read(ByteBufferSet dest) throws IOException, NeedsTaskException {
		checkReadBuffer(dest);
		if (!dest.hasRemaining())
			return 0;
		if (invalid)
			return -1;
		if (!initialHandshaked) {
			doHandshake();
		}
		readLock.lock();
		try {
			long transfered = transferPendingPlain(dest);
			if (transfered > 0)
				return transfered;
			HandshakeStatus handshakeStatus = engine.getHandshakeStatus();
			while (true) {
				Util.assertTrue(inPlain.position() == 0);
				if (tlsClosePending) {
					close();
					return -1;
				}
				switch (handshakeStatus) {
				case NEED_UNWRAP:
				case NEED_WRAP:
					// handshake needs both read and write locks, we already
					// have one, take the other
					writeLock.lock();
					try {
						int result = handshakeImpl(Optional.of(dest), Optional.of(handshakeStatus), false /* active */);
						if (result > 0) {
							if (inPlain.position() == 0) {
								return result;
							} else {
								return transferPendingPlain(dest);
							}
						}
						handshakeStatus = HandshakeStatus.NOT_HANDSHAKING;
					} finally {
						writeLock.unlock();
					}
					break;
				case NOT_HANDSHAKING:
				case FINISHED:
					try {
						LoopResult res = unwrapLoop(Optional.of(dest), NOT_HANDSHAKING /* statusLoopCondition */);
						handshakeStatus = res.lastHandshakeStatus;
						while (res.bytes == 0 && handshakeStatus == NOT_HANDSHAKING) {
							if (!inEncrypted.hasRemaining())
								enlargeInEncrypted();
							int c = readFromNetwork(); // IO block
							if (c == 0) {
								throw new NeedsReadException();
							}
							res = unwrapLoop(Optional.of(dest), NOT_HANDSHAKING /* statusLoopCondition */);
							handshakeStatus = res.lastHandshakeStatus;
						}
						if (res.bytes > 0) {
							if (inPlain.position() == 0) {
								return res.bytes;
							} else {
								return transferPendingPlain(dest);
							}
						}
						// exit loop after we either have something to answer or
						// the counterpart wants a handshake, or we need to run
						// a task
					} catch (EOFException e) {
						return -1;
					}
					break;
				case NEED_TASK:
					if (runTasks) {
						engine.getDelegatedTask().run();
						handshakeStatus = engine.getHandshakeStatus();
					} else {
						throw new NeedsTaskException(engine.getDelegatedTask());
					}
					break;
				}
			}
		} finally {
			readLock.unlock();
		}
	}

	private int transferPendingPlain(ByteBufferSet dstBuffers) {
		inPlain.flip(); // will read
		int bytes = dstBuffers.putRemaining(inPlain);
		inPlain.compact(); // will write
		return bytes;
	}

	private LoopResult unwrapLoop(Optional<ByteBufferSet> dest, HandshakeStatus statusLoopCondition)
			throws SSLException, EOFException {
		ByteBufferSet effDest = dest.orElse(new ByteBufferSet(inPlain));
		inEncrypted.flip();
		try {
			SSLEngineResult result;
			do {
				Util.assertTrue(inPlain.position() == 0);
				try {
					result = engine.unwrap(inEncrypted, effDest.array, effDest.offset, effDest.length);
					if (logger.isTraceEnabled()) {
						logger.trace("engine.unwrap() result [{}]. Engine status: {}; inEncrypted {}; inPlain: {}",
								resultToString(result), result.getHandshakeStatus(), inEncrypted, effDest);
					}
				} catch (SSLException e) {
					// something bad was received from the network, we cannot
					// continue
					invalid = true;
					throw e;
				}
				switch (result.getStatus()) {
				case OK:
					if (result.bytesProduced() > 0) {
						return new LoopResult(result.bytesProduced(), result.getHandshakeStatus());
					}
					break;
				case BUFFER_UNDERFLOW:
					return new LoopResult(result.bytesProduced(), result.getHandshakeStatus());
				case BUFFER_OVERFLOW:
					/*
					 * The engine can respond overflow even data was already
					 * written. In that case, return it.
					 */
					if (result.bytesProduced() > 0) {
						result.bytesProduced();
					}
					if (dest.isPresent() && effDest == dest.get()) {
						/*
						 * The client-supplier buffer is not big enough. Use the
						 * internal inPlain buffer, also ensure that it is
						 * bigger than the too-small supplied one.
						 */
						ensureInPlainCapacity(((int) dest.get().remaining()) * 2);
					} else {
						enlargeInPlain();
					}
					// inPlain changed, re-create the wrapper
					effDest = new ByteBufferSet(inPlain);
					break;
				case CLOSED:
					tlsClosePending = true;
					if (result.bytesProduced() > 0)
						result.bytesProduced();
					else
						throw new EOFException();
				}
			} while (result.getHandshakeStatus() == statusLoopCondition);
			return new LoopResult(0, result.getHandshakeStatus());
		} finally {
			inEncrypted.compact();
		}
	}

	private int readFromNetwork() throws IOException {
		Util.assertTrue(inEncrypted.hasRemaining());
		int res;
		try {
			logger.trace("Reading from network");
			res = readChannel.read(inEncrypted);// IO block
			logger.trace("Read from network; inEncrypted: {}", inEncrypted);
		} catch (IOException e) {
			// after a failed read, buffers can be in any state, close
			invalid = true;
			throw e;
		}
		if (res == -1) {
			invalid = true;
			throw new EOFException();
		}
		return res;
	}

	// write

	public long write(ByteBufferSet source) throws IOException {
		long bytesToConsume = source.remaining();
		if (bytesToConsume == 0)
			return 0;
		if (invalid)
			throw new ClosedChannelException();
		if (!initialHandshaked)
			doHandshake();
		long bytesConsumed = 0;
		writeLock.lock();
		try {
			if (invalid)
				throw new ClosedChannelException();
			HandshakeStatus handshakeStatus = engine.getHandshakeStatus();
			if (handshakeStatus != NOT_HANDSHAKING) {
				readLock.lock();
				try {
					handshakeImpl(Optional.empty(), Optional.of(handshakeStatus), true /* active */);
				} finally {
					readLock.unlock();
				}
			}
			while (true) {
				if (outEncrypted.position() > 0) {
					flipAndWriteToNetwork(); // IO block
					if (outEncrypted.position() > 0) {
						/*
						 * Could not write everything, will not wrap any more.
						 * Also, at this point we know that the socket is
						 * non-blocking
						 */
						if (bytesConsumed > 0)
							return bytesConsumed;
						else
							throw new NeedsWriteException();
					}
				}
				if (bytesConsumed == bytesToConsume)
					return bytesToConsume;
				LoopResult res = wrapLoop(source);
				Util.assertTrue(res.lastHandshakeStatus == NOT_HANDSHAKING);
				bytesConsumed += res.bytes;
			}
		} finally {
			writeLock.unlock();
		}
	}

	private LoopResult wrapLoop(ByteBufferSet source) throws SSLException, AssertionError, ClosedChannelException {
		int bytesConsumed = 0;
		SSLEngineResult result;
		do {
			result = engine.wrap(source.array, source.offset, source.length, outEncrypted);
			if (logger.isTraceEnabled()) {
				logger.trace("engine.wrap() result: [{}]; engine status: {}; srcBuffer: {}, outEncrypted: {}",
						resultToString(result), result.getHandshakeStatus(), source, outEncrypted);
			}
			switch (result.getStatus()) {
			case OK:
				break;
			case BUFFER_OVERFLOW:
				enlargeOutEncrypted();
				break;
			case CLOSED:
				invalid = true;
				throw new ClosedChannelException();
			case BUFFER_UNDERFLOW:
				// it does not make sense to ask more data from a client if
				// it does not have any more
				throw new IllegalStateException("wrap() returned BUFFER_UNDERFLOW");
			}
			bytesConsumed += result.bytesConsumed();
		} while (result.getStatus() == Status.BUFFER_OVERFLOW);
		logger.trace("Returning bytes consumed: {}", bytesConsumed);
		return new LoopResult(bytesConsumed, result.getHandshakeStatus());
	}

	private void enlargeOutEncrypted() {
		outEncrypted = enlarge(outEncrypted, "outEncrypted", tlsMaxRecordSize);
	}

	private void enlargeInPlain() {
		inPlain = enlarge(inPlain, "inPlain", tlsMaxDataSize);
	}

	private void enlargeInEncrypted() {
		inEncrypted = enlarge(inEncrypted, "inEncrypted", tlsMaxRecordSize);
	}

	private void ensureInPlainCapacity(int newCapacity) {
		if (inPlain.capacity() < newCapacity)
			inPlain = resize(inPlain, newCapacity);
	}

	private static ByteBuffer enlarge(ByteBuffer buffer, String name, int maxSize) {
		if (buffer.capacity() >= maxSize) {
			throw new IllegalStateException(
					String.format("%s buffer insufficient despite having capacity of %d", name, buffer.capacity()));
		}
		int newCapacity = Math.min(buffer.capacity() * 2, maxSize);
		logger.trace("{} buffer too small, increasing from {} to {}", name, buffer.capacity(), newCapacity);
		return resize(buffer, newCapacity);
	}

	private static ByteBuffer resize(ByteBuffer buffer, int newCapacity) {
		ByteBuffer newBuffer = ByteBuffer.allocate(newCapacity);
		buffer.flip();
		newBuffer.put(buffer);
		buffer.compact();
		return newBuffer;
	}

	private int flipAndWriteToNetwork() throws IOException {
		outEncrypted.flip();
		try {
			int bytesWritten = 0;
			while (outEncrypted.hasRemaining()) {
				try {
					int c = writeToNetwork(outEncrypted);
					if (c == 0) {
						/*
						 * If no bytes were written, it means that the socket is
						 * non-blocking and needs more buffer space, so stop the
						 * loop
						 */
						return bytesWritten;
					}
					bytesWritten += c;
				} catch (IOException e) {
					// after a failed write, buffers can be in any state, close
					invalid = true;
					throw e;
				}
				// blocking SocketChannels can write less than all the bytes
				// just before an error the loop forces the exception
			}
			return bytesWritten;
		} finally {
			outEncrypted.compact();
		}
	}

	protected int writeToNetwork(ByteBuffer out) throws IOException {
		logger.trace("Writing to network: {}", out);
		return writeChannel.write(out);
	}

	// handshake and close

	/**
	 * Force new handshake
	 * 
	 * @throws IOException
	 */
	public void renegotiate() throws IOException {
		if (!initialHandshaked)
			doHandshake();
		readLock.lock();
		try {
			writeLock.lock();
			try {
				handshakeImpl(Optional.empty(), Optional.empty(), true /* active */);
			} finally {
				writeLock.unlock();
			}
		} finally {
			readLock.unlock();
		}
	}

	/**
	 * Initial handshake
	 * 
	 * @throws IOException
	 */
	public void doHandshake() throws IOException {
		initLock.lock();
		try {
			if (!initialHandshaked) {
				readLock.lock();
				try {
					writeLock.lock();
					try {
						handshakeImpl(Optional.empty(), Optional.empty(), true /* active */);
					} finally {
						writeLock.unlock();
					}
				} finally {
					readLock.unlock();
				}
				// call client code
				initSessionCallback.accept(engine.getSession());
				initialHandshaked = true;
			}
		} finally {
			initLock.unlock();
		}
	}

	private int handshakeImpl(Optional<ByteBufferSet> dest, Optional<HandshakeStatus> handshakeStatus, boolean active) throws IOException {
		Util.assertTrue(inPlain.position() == 0);
		// write any pending bytes
		int bytesToWrite = outEncrypted.position();
		if (bytesToWrite > 0) {
			int c = flipAndWriteToNetwork(); // IO block
			if (c < bytesToWrite)
				throw new NeedsWriteException();
		}
		if (active) {
			engine.beginHandshake();
			logger.trace("Called engine.beginHandshake()");
		}
		return handShakeLoop(dest, handshakeStatus);
	}

	private int handShakeLoop(Optional<ByteBufferSet> dest, Optional<HandshakeStatus> handshakeStatus) throws IOException, TlsNonBlockingNecessityException {
		Util.assertTrue(inPlain.position() == 0);
		try {
			HandshakeStatus status = handshakeStatus.orElseGet(() ->engine.getHandshakeStatus());
			while (true) {
				switch (status) {
				case NEED_WRAP:
					Util.assertTrue(outEncrypted.position() == 0);
					LoopResult wrapResult = wrapLoop(dummyOut);
					status = wrapResult.lastHandshakeStatus;
					int bytesToWrite = outEncrypted.position();
					int c = flipAndWriteToNetwork(); // IO block
					if (c < bytesToWrite)
						throw new NeedsWriteException();
					break;
				case NEED_UNWRAP:
					Util.assertTrue(inPlain.position() == 0);
					LoopResult res = unwrapLoop(dest, NEED_UNWRAP /* statusLoopCondition */);
					status = res.lastHandshakeStatus;
					while (status == NEED_UNWRAP && res.bytes == 0) {
						if (!inEncrypted.hasRemaining())
							enlargeInEncrypted();
						int bytesRead = readFromNetwork(); // IO block
						if (bytesRead == 0)
							throw new NeedsReadException();
						Util.assertTrue(inEncrypted.position() > 0);
						res = unwrapLoop(dest, NEED_UNWRAP /* statusLoopCondition */);
						status = res.lastHandshakeStatus;
					}
					/*
					 * It is possible that in the middle of the handshake loop,
					 * even when the resulting status is NEED_UNWRAP, some bytes
					 * are read in the inPlain buffer. If that is the case,
					 * interrupt the loop.
					 */
					if (res.bytes > 0)
						return res.bytes;
					break;
				case NOT_HANDSHAKING:
					throw new IllegalStateException(
							"Handshake completion should be signaled with 'FINISHED', not 'NOT_HANDSHAKING'");
				case NEED_TASK:
					if (runTasks) {
						engine.getDelegatedTask().run();
						status = engine.getHandshakeStatus();
					} else {
						throw new NeedsTaskException(engine.getDelegatedTask());
					}
					break;
				case FINISHED:
					return 0;
				}
			}
		} catch (TlsNonBlockingNecessityException e) {
			throw e;
		}
	}

	public void close() {
		writeLock.lock();
		try {
			if (!invalid) {
				engine.closeOutbound();
				if (engine.getHandshakeStatus() == NEED_WRAP) {
					// close notify alert only, does not await for peer
					// response.
					Util.assertTrue(outEncrypted.position() == 0);
					try {
						SSLEngineResult result = engine.wrap(dummyOut.array, dummyOut.offset, dummyOut.length,
								outEncrypted);
						if (logger.isTraceEnabled()) {
							logger.trace("engine.wrap() result: [{}]; engine status: {}; outEncrypted: {}",
									resultToString(result), result.getHandshakeStatus(), outEncrypted);
						}
						Util.assertTrue(result.getStatus() == Status.CLOSED);
						flipAndWriteToNetwork(); // IO block
					} catch (Exception e) {
						// graceful close of TLS connection failed.
					}
				}
				invalid = true;
			}
			Util.closeChannel(writeChannel);
			Util.closeChannel(readChannel);
		} finally {
			writeLock.unlock();
		}
	}

	public boolean isOpen() {
		return writeChannel.isOpen() && readChannel.isOpen();
	}

	static void checkReadBuffer(ByteBufferSet dest) {
		if (dest.isReadOnly())
			throw new IllegalArgumentException();
	}

	public SSLEngine engine() {
		return engine;
	}

	public boolean getRunTasks() {
		return runTasks;
	}

	/**
	 * Convert a {@link SSLEngineResult} into a {@link String}, this is needed
	 * because the supplied method includes a log-breaking newline.
	 */
	private static String resultToString(SSLEngineResult result) {
		return String.format("status=%s,handshakeStatus=%s,bytesProduced=%d,bytesConsumed=%d", result.getStatus(),
				result.getHandshakeStatus(), result.bytesProduced(), result.bytesConsumed());
	}

}
