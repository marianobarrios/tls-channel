package tlschannel.impl;

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
	 * Official TLS max data size is 2^14 = 16k. Use 1024 more to account for the overhead
	 */
	private final static int maxTlsPacketSize = 17 * 1024;
	
	private static class EngineLoopResult {
		public final int bytes;
		public final HandshakeStatus lastHandshakeStatus;

		public EngineLoopResult(int bytes, HandshakeStatus lastHandshakeStatus) {
			this.bytes = bytes;
			this.lastHandshakeStatus = lastHandshakeStatus;
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

	// @formatter:off
	public TlsChannelImpl(
			ReadableByteChannel readChannel, 
			WritableByteChannel writeChannel, 
			SSLEngine engine,
			Optional<ByteBuffer> inEncrypted, 
			Consumer<SSLSession> initSessionCallback, 
			boolean runTasks,
			BufferAllocator plainBufferAllocator,
			BufferAllocator encryptedBufferAllocator) {
	// @formatter:on
		this.readChannel = readChannel;
		this.writeChannel = writeChannel;
		this.engine = engine;
		this.inEncrypted = inEncrypted.orElseGet(() -> encryptedBufferAllocator.allocate(buffersInitialSize));
		this.initSessionCallback = initSessionCallback;
		this.runTasks = runTasks;
		this.plainBufferAllocator = plainBufferAllocator;
		this.encryptedBufferAllocator = encryptedBufferAllocator;
		inPlain = plainBufferAllocator.allocate(buffersInitialSize);
		outEncrypted = encryptedBufferAllocator.allocate(buffersInitialSize);
	}

	private final Lock initLock = new ReentrantLock();
	private final Lock readLock = new ReentrantLock();
	private final Lock writeLock = new ReentrantLock();

	private volatile boolean negotiated = false;
	private volatile boolean invalid = false;
	private boolean tlsClosePending = false;

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
		if (invalid)
			return -1;
		negotiateIfNecesary();
		readLock.lock();
		try {
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
				Util.assertTrue(inPlain.position() == 0);
				if (tlsClosePending) {
					close();
					return -1;
				}
				switch (handshakeStatus) {
				case NEED_UNWRAP:
				case NEED_WRAP:
					bytesToReturn = handshake(Optional.of(dest), Optional.of(handshakeStatus));
					handshakeStatus = NOT_HANDSHAKING;
					break;
				case NOT_HANDSHAKING:
				case FINISHED:
					try {
						EngineLoopResult res = readLoop(Optional.of(dest), NOT_HANDSHAKING);
						bytesToReturn = res.bytes;
						handshakeStatus = res.lastHandshakeStatus;
					} catch (EOFException e) {
						return -1;
					}
					break;
				case NEED_TASK:
					handleTask();
					handshakeStatus = engine.getHandshakeStatus();
					break;
				}
			}
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
		return bytes;
	}

	private EngineLoopResult unwrapLoop(Optional<ByteBufferSet> dest, HandshakeStatus statusLoopCondition)
			throws SSLException, EOFException {
		ByteBufferSet effDest = dest.orElseGet(() -> new ByteBufferSet(inPlain));
		while (true) {
			Util.assertTrue(inPlain.position() == 0);
			SSLEngineResult result = callEngineUnwrap(effDest);
			if (result.getStatus() == Status.CLOSED) {
				tlsClosePending = true;
				throw new EOFException();
			}
			/*
			 * Note that data can be returned even in case of overflow, in that
			 * case, just return the data.
			 */
			if (result.bytesProduced() > 0 || result.getStatus() == Status.BUFFER_UNDERFLOW
					|| result.getHandshakeStatus() != statusLoopCondition) {
				return new EngineLoopResult(result.bytesProduced(), result.getHandshakeStatus());
			}
			if (result.getStatus() == Status.BUFFER_OVERFLOW) {
				if (dest.isPresent() && effDest == dest.get()) {
					/*
					 * The client-supplier buffer is not big enough. Use the
					 * internal inPlain buffer, also ensure that it is bigger
					 * than the too-small supplied one.
					 */
					ensureInPlainCapacity(((int) dest.get().remaining()) * 2);
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

	private int readFromNetwork() throws IOException {
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

	public static int readFromNetwork(ReadableByteChannel readChannel, ByteBuffer buffer) throws IOException {
		Util.assertTrue(buffer.hasRemaining());
		logger.trace("Reading from network");
		int res = readChannel.read(buffer); // IO block
		logger.trace("Read from network; buffer: {}", buffer);
		if (res == -1) {
			throw new EOFException();
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
		if (invalid)
			throw new ClosedChannelException();
		negotiateIfNecesary();
		long bytesConsumed = 0;
		writeLock.lock();
		try {
			if (invalid)
				throw new ClosedChannelException();
			while (true) {
				writeToNetworkIfNecessary();
				if (bytesConsumed == bytesToConsume)
					return bytesToConsume;
				EngineLoopResult res = wrapLoop(source);
				bytesConsumed += res.bytes;
			}
		} finally {
			writeLock.unlock();
		}
	}

	private void writeToNetworkIfNecessary() throws IOException {
		int bytesToWrite = outEncrypted.position();
		if (bytesToWrite > 0) {
			int c = flipAndWriteToNetwork(); // IO block
			if (c < bytesToWrite)
				throw new NeedsWriteException();
		}
	}

	private EngineLoopResult wrapLoop(ByteBufferSet source) throws SSLException, ClosedChannelException {
		while (true) {
			SSLEngineResult result = callEngineWrap(source);
			switch (result.getStatus()) {
			case OK:
				return new EngineLoopResult(result.bytesConsumed(), result.getHandshakeStatus());
			case BUFFER_OVERFLOW:
				Util.assertTrue(result.bytesConsumed() == 0);
				enlargeOutEncrypted();
				break;
			case CLOSED:
				invalid = true;
				throw new ClosedChannelException();
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
		outEncrypted = Util.enlarge(encryptedBufferAllocator, outEncrypted, "outEncrypted", maxTlsPacketSize);
	}

	private void enlargeInPlain() {
		inPlain = Util.enlarge(plainBufferAllocator, inPlain, "inPlain", maxTlsPacketSize);
	}

	private void enlargeInEncrypted() {
		inEncrypted = Util.enlarge(encryptedBufferAllocator, inEncrypted, "inEncrypted", maxTlsPacketSize);
	}

	private void ensureInPlainCapacity(int newCapacity) {
		if (inPlain.capacity() < newCapacity)
			inPlain = Util.resize(plainBufferAllocator, inPlain, newCapacity);
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
	 * Force new negotiation
	 */
	public void renegotiate() throws IOException {
		negotiate(true /* force */);
	}

	/**
	 * Do a negotiation if this connection is new and it hasn't been done
	 * already.
	 */
	public void negotiateIfNecesary() throws IOException {
		negotiate(false /* force */);
	}

	private void negotiate(boolean force) throws IOException {
		if (!force && negotiated)
			return;
		initLock.lock();
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

	private int handshake(Optional<ByteBufferSet> dest, Optional<HandshakeStatus> handshakeStatus) throws IOException {
		readLock.lock();
		try {
			writeLock.lock();
			try {
				Util.assertTrue(inPlain.position() == 0);
				writeToNetworkIfNecessary();
				return handshakeLoop(dest, handshakeStatus);
			} finally {
				writeLock.unlock();
			}
		} finally {
			readLock.unlock();
		}
	}

	private int handshakeLoop(Optional<ByteBufferSet> dest, Optional<HandshakeStatus> handshakeStatus)
			throws IOException {
		Util.assertTrue(inPlain.position() == 0);
		HandshakeStatus status = handshakeStatus.orElseGet(() -> engine.getHandshakeStatus());
		while (true) {
			switch (status) {
			case NEED_WRAP:
				Util.assertTrue(outEncrypted.position() == 0);
				EngineLoopResult wrapResult = wrapLoop(dummyOut);
				status = wrapResult.lastHandshakeStatus;
				writeToNetworkIfNecessary(); // IO block
				break;
			case NEED_UNWRAP:
				EngineLoopResult res = readLoop(dest, NEED_UNWRAP /* statusLoopCondition */);
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

	private EngineLoopResult readLoop(Optional<ByteBufferSet> dest, HandshakeStatus statusLoopCondition)
			throws IOException {
		while (true) {
			Util.assertTrue(inPlain.position() == 0);
			inEncrypted.flip();
			try {
				EngineLoopResult res = unwrapLoop(dest, statusLoopCondition);
				if (res.bytes > 0 || res.lastHandshakeStatus != statusLoopCondition) {
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
									Util.resultToString(result), result.getHandshakeStatus(), outEncrypted);
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
		plainBufferAllocator.free(inPlain);
		encryptedBufferAllocator.free(inEncrypted);
		encryptedBufferAllocator.free(outEncrypted);
	}

	public boolean isOpen() {
		return writeChannel.isOpen() && readChannel.isOpen();
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

}
