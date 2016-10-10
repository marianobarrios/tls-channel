package tlschannel;

import javax.net.ssl.SSLEngineResult.HandshakeStatus;

import java.util.Arrays;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLEngine;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.IllegalSelectorException;

import javax.net.ssl.SSLHandshakeException;
import static javax.net.ssl.SSLEngineResult.HandshakeStatus.*;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

public class TlsSocketChannelImpl {

	private static final Logger logger = LoggerFactory.getLogger(TlsSocketChannelImpl.class);

	private final ReadableByteChannel readChannel;
	private final WritableByteChannel writeChannel;
	private final SSLEngine engine;
	private final ByteBuffer inEncrypted;
	private final Consumer<SSLSession> initSessionCallback;

	public TlsSocketChannelImpl(ReadableByteChannel readChannel, WritableByteChannel writeChannel, SSLEngine engine,
			ByteBuffer inEncrypted, Consumer<SSLSession> initSessionCallback) {
		if (inEncrypted.capacity() < TlsSocketChannelImpl.tlsMaxRecordSize)
			throw new IllegalArgumentException(String.format("inEncrypted capacity must be at least %d bytes (was %d)",
					TlsSocketChannelImpl.tlsMaxRecordSize, inEncrypted.capacity()));
		this.readChannel = readChannel;
		this.writeChannel = writeChannel;
		this.engine = engine;
		this.inEncrypted = inEncrypted;
		this.initSessionCallback = initSessionCallback;
	}

	private final Lock initLock = new ReentrantLock();
	private final Lock readLock = new ReentrantLock();
	private final Lock writeLock = new ReentrantLock();

	private volatile boolean initialHandshaked = false;
	private volatile boolean invalid = false;
	private boolean tlsClosePending = false;

	// decrypted data from inEncrypted
	private final ByteBuffer inPlain = ByteBuffer.allocate(TlsSocketChannelImpl.tlsMaxDataSize);

	// contains data encrypted to send to the network
	private final ByteBuffer outEncrypted = ByteBuffer.allocate(TlsSocketChannelImpl.tlsMaxRecordSize);

	// handshake wrap() method calls need a buffer to read from, even when they
	// actually do not read anything
	private final ByteBuffer[] dummyOut = new ByteBuffer[] { };

	// read

	public long read(ByteBuffer[] dstBuffers, int offset, int length) throws IOException {
		TlsSocketChannelImpl.checkReadBuffer(dstBuffers, offset, length);
		long dstRemaining = Arrays.stream(dstBuffers, offset, offset + length).mapToLong(b -> b.remaining()).sum();
		if (dstRemaining == 0)
			return 0;
		if (invalid)
			return -1;
		if (!initialHandshaked) {
			doHandshake();
		}
		readLock.lock();
		try {
			while (true) {
				long transfered = transferPendingPlain(dstBuffers, offset, length);
				if (transfered > 0)
					return transfered;
				Util.assertTrue(inPlain.position() == 0);
				if (tlsClosePending) {
					close();
					return -1;
				}
				switch (engine.getHandshakeStatus()) {
				case NEED_UNWRAP:
				case NEED_WRAP:
					// handshake needs both read and write locks, we already
					// have one, take the other
					writeLock.lock();
					try {
						handshakeImpl(false /* active */);
					} finally {
						writeLock.unlock();
					}
					break;
				case NOT_HANDSHAKING:
					try {
						unwrapLoop(NOT_HANDSHAKING /* statusLoopCondition */);
						while (inPlain.position() == 0 && engine.getHandshakeStatus() == NOT_HANDSHAKING) {
							int c = readFromNetwork(); // IO block
							if (c == 0) {
								long t = transferPendingPlain(dstBuffers, offset, length);
								if (t > 0)
									return t;
								else
									throw new NeedsReadException();
							}
							unwrapLoop(NOT_HANDSHAKING /* statusLoopCondition */);
						}
						// exit loop after we either have something to answer or
						// the counterpart wants a handshake, or we need to run a task
					} catch (EOFException e) {
						return -1;
					}
					break;
				case NEED_TASK:
					engine.getDelegatedTask().run();
					break;
				case FINISHED:
					throw new IllegalStateException("engine.getHandshakeStatus() returned FINISHED");
				}
			}
		} finally {
			readLock.unlock();
		}
	}

	private long transferPendingPlain(ByteBuffer[] dstBuffers, int offset, int length) {
		inPlain.flip(); // will read
		long totalBytes = 0;
		for (int i = offset; i < offset + length; i++) {
			if (!inPlain.hasRemaining())
				break;
			ByteBuffer dstBuffer = dstBuffers[i];
			int bytes = Math.min(inPlain.remaining(), dstBuffer.remaining());
			dstBuffer.put(inPlain.array(), inPlain.position(), bytes);
			inPlain.position(inPlain.position() + bytes);
			totalBytes += bytes;
		}
		inPlain.compact(); // will write
		return totalBytes;
	}

	private void unwrapLoop(HandshakeStatus statusLoopCondition) throws SSLException, EOFException {
		inEncrypted.flip();
		SSLEngineResult result = null;
		Util.assertTrue(inPlain.position() == 0);
		try {
			do {
				try {
					result = engine.unwrap(inEncrypted, inPlain);
					if (logger.isTraceEnabled()) {
						logger.trace("engine.unwrap() result [{}]. Engine status: {}; inEncrypted {}; inPlain: {}",
								resultToString(result), engine.getHandshakeStatus(), inEncrypted, inPlain);
					}
				} catch (SSLException e) {
					// something bad was received from the network, we cannot
					// continue
					invalid = true;
					throw e;
				}
				switch (result.getStatus()) {
				case OK:
				case BUFFER_UNDERFLOW:
					// nothing
					break;
				case BUFFER_OVERFLOW:
					/*
					 * The engine can respond overflow even where there also is
					 * underflow (apparently the check is before). Our inPlain
					 * buffer should be big enough, so an overflow should mean
					 * that everything was decrypted, and more data is needed.
					 * So the inPlain must contain something.
					 */
					Util.assertTrue(inPlain.position() > 0);
					break;
				case CLOSED:
					tlsClosePending = true;
					if (inPlain.position() == 0)
						throw new EOFException();
					break;
				}
			} while (result.getStatus() == Status.OK && engine.getHandshakeStatus() == statusLoopCondition);
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

	public long write(ByteBuffer[] srcBuffers, int offset, int length) throws IOException {
		TlsSocketChannelImpl.checkWriteBuffer(srcBuffers, offset, length);
		long bytesToConsume = Arrays.stream(srcBuffers, offset, offset + length).mapToLong(bb -> bb.remaining()).sum();
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
			Util.assertTrue(engine.getHandshakeStatus() == NOT_HANDSHAKING);
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
				int c = wrapLoop(srcBuffers, offset, length);
				Util.assertTrue(engine.getHandshakeStatus() == NOT_HANDSHAKING);
				bytesConsumed += c;
			}
		} finally {
			writeLock.unlock();
		}
	}

	private int wrapLoop(ByteBuffer[] outPlain, int effOffset, int effLength)
			throws SSLException, AssertionError, ClosedChannelException {
		SSLEngineResult result = engine.wrap(outPlain, effOffset, effLength, outEncrypted);
		if (logger.isTraceEnabled()) {
			logger.trace("engine.wrap() result: [{}]; engine status: {}; srcBuffer: {}, outEncripted: {}",
					resultToString(result), engine.getHandshakeStatus(),
					Arrays.stream(outPlain, effOffset, effOffset + effLength).collect(Collectors.toList()),
					outEncrypted);
		}
		switch (result.getStatus()) {
		case OK:
			break;
		case BUFFER_OVERFLOW:
			// this could happen in theory, but does not happen if
			// outEncrypted is at least of packet size
			throw new AssertionError();
		case CLOSED:
			invalid = true;
			throw new ClosedChannelException();
		case BUFFER_UNDERFLOW:
			// it does not make sense to ask more data from a client if
			// it does not have any more
			throw new IllegalStateException("wrap returned BUFFER_UNDERFLOW");
		}
		return result.bytesConsumed();
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
				handshakeImpl(true /* active */);
			} finally {
				writeLock.unlock();
			}
		} finally {
			readLock.unlock();
		}
	}

	/**
	 * Process a new handshake initiated by the counter party
	 * 
	 * @throws IOException
	 */
	public void doPassiveHandshake() throws IOException {
		if (!initialHandshaked)
			doHandshake();
		readLock.lock();
		try {
			writeLock.lock();
			try {
				handshakeImpl(false /* active */);
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
						handshakeImpl(true /* active */);
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

	private void handshakeImpl(boolean active) throws IOException {
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
		handShakeLoop();
	}

	private void handShakeLoop() throws SSLHandshakeException, TlsNonBlockingNecessityException {
		Util.assertTrue(inPlain.position() == 0);
		try {
			while (true) {
				switch (engine.getHandshakeStatus()) {
				case NEED_WRAP:
					Util.assertTrue(outEncrypted.position() == 0);
					wrapLoop(dummyOut, 0, 0);
					int bytesToWrite = outEncrypted.position();
					int c = flipAndWriteToNetwork(); // IO block
					if (c < bytesToWrite)
						throw new NeedsWriteException();
					break;
				case NEED_UNWRAP:
					Util.assertTrue(inPlain.position() == 0);
					unwrapLoop(NEED_UNWRAP /* statusLoopCondition */);
					while (engine.getHandshakeStatus() == NEED_UNWRAP && inPlain.position() == 0) {
						int bytesRead = readFromNetwork(); // IO block
						if (bytesRead == 0)
							throw new NeedsReadException();
						Util.assertTrue(inEncrypted.position() > 0);
						unwrapLoop(NEED_UNWRAP /* statusLoopCondition */);
					}
					/*
					 * It is possible that in the middle of the handshake loop,
					 * even when the resulting status is NEED_UNWRAP, some bytes
					 * are read in the inPlain buffer. If that is the case,
					 * interrupt the loop.
					 */
					if (inPlain.position() > 0)
						return;
					break;
				case NOT_HANDSHAKING:
					return;
				case NEED_TASK:
					engine.getDelegatedTask().run();
					break;
				case FINISHED:
					throw new IllegalStateException("engine.getHandshakeStatus() returned FINISHED");
				}
			}
		} catch (TlsNonBlockingNecessityException e) {
			throw e;
		} catch (IOException e) {
			String reason;
			if (e.getMessage() == null || e.getMessage().isEmpty()) {
				reason = e.getClass().getCanonicalName();
			} else {
				reason = e.getMessage();
			}
			// TODO: why hide exception? (can be a real IO problem)
			throw new SSLHandshakeException("Handshaking aborted. Reason: " + reason);
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
						SSLEngineResult result = engine.wrap(dummyOut, outEncrypted);
						if (logger.isTraceEnabled()) {
							logger.trace("engine.wrap() result: [{}]; engine status: {}; outEncrypted: {}",
									resultToString(result), engine.getHandshakeStatus(), outEncrypted);
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

	static void checkReadBuffer(ByteBuffer[] dstBuffers, int offset, int length) {
		if (dstBuffers == null)
			throw new NullPointerException();
		for (int i = offset; i < offset + length; i++) {
			if (dstBuffers[i] == null)
				throw new NullPointerException();
			if (dstBuffers[i].isReadOnly())
				throw new IllegalArgumentException();
		}
	}

	public static void checkWriteBuffer(ByteBuffer[] srcBuffers, int offset, int length) {
		if (srcBuffers == null)
			throw new NullPointerException();
		for (int i = offset; i < offset + length; i++) {
			if (srcBuffers[i] == null)
				throw new NullPointerException();
		}
	}

	// TODO: Find out why this is needed even if the TLS max size is 2^14
	static final int tlsMaxDataSize = 32768; // 2^15 bytes of data

	// @formatter:off
	static int tlsMaxRecordSize = 
			5 + // header
			256 + // IV
			32768 + // 2^15 bytes of data
			256 + // max padding
			20; // SHA1 hash
	// @formatter:on

	public SSLEngine engine() {
		return engine;
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
