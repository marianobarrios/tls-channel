package tlschannel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.Channel;
import java.nio.channels.ClosedChannelException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.StandardConstants;

import tlschannel.impl.*;
import tlschannel.impl.TlsChannelImpl.EofException;
import tlschannel.util.Util;

/**
 * A server-side {@link TlsChannel}.
 */
public class ServerTlsChannel implements TlsChannel {

	@FunctionalInterface
	private interface SniReader {
		Optional<String> readSni() throws IOException, EofException;
	}

	private interface InternalSslContextFactory {
		SSLContext getSslContext(SniReader sniReader) throws IOException, EofException;
	}

	private static class SniSslContextFactory implements InternalSslContextFactory {

		private Function<Optional<String>, SSLContext> function;

		public SniSslContextFactory(Function<Optional<String>, SSLContext> function) {
			this.function = function;
		}

		@Override
		public SSLContext getSslContext(SniReader sniReader) throws IOException, EofException {
			// IO block
			Optional<String> nameOpt = sniReader.readSni();
			// call client code
			return function.apply(nameOpt);
		}

	}

	private static class FixedSslContextFactory implements InternalSslContextFactory {

		private final SSLContext sslContext;

		public FixedSslContextFactory(SSLContext sslContext) {
			this.sslContext = sslContext;
		}

		@Override
		public SSLContext getSslContext(SniReader sniReader) {
			/*
			 * Avoid SNI parsing (using the supplied sniReader) when no decision
			 * would be made based on it.
			 */
			return sslContext;
		}

	}

	private static SSLEngine defaultSSLEngineFactory(SSLContext sslContext) {
		SSLEngine engine = sslContext.createSSLEngine();
		engine.setUseClientMode(false);
		return engine;
	}

	/**
	 * Builder of {@link ServerTlsChannel}
	 */
	public static class Builder extends TlsChannelBuilder<Builder> {

		private final InternalSslContextFactory internalSslContextFactory;
		private Function<SSLContext, SSLEngine> sslEngineFactory = ServerTlsChannel::defaultSSLEngineFactory;

		private Builder(ByteChannel underlying, SSLContext sslContext) {
			super(underlying);
			this.internalSslContextFactory = new FixedSslContextFactory(sslContext);
		}

		private Builder(ByteChannel wrapped, Function<Optional<String>, SSLContext> sslContextFactory) {
			super(wrapped);
			this.internalSslContextFactory = new SniSslContextFactory(sslContextFactory);
		}

		@Override
		Builder getThis() {
			return this;
		}

		public Builder withEngineFactory(Function<SSLContext, SSLEngine> sslEngineFactory) {
			this.sslEngineFactory = sslEngineFactory;
			return this;
		}

		public ServerTlsChannel build() {
			return new ServerTlsChannel(underlying, internalSslContextFactory, sslEngineFactory, sessionInitCallback,
					runTasks, plainBufferAllocator, encryptedBufferAllocator, releaseBuffers, waitForCloseConfirmation);
		}

	}

	/**
	 * Create a new {@link Builder}, configured with a underlying
	 * {@link Channel} and a fixed {@link SSLContext}, which will be used to
	 * create the {@link SSLEngine}.
	 * 
	 * @param underlying
	 *            a reference to the underlying {@link ByteChannel}
	 * @param sslContext
	 *            a fixed {@link SSLContext} to be used
	 */
	public static Builder newBuilder(ByteChannel underlying, SSLContext sslContext) {
		return new Builder(underlying, sslContext);
	}

	/**
	 * Create a new {@link Builder}, configured with a underlying
	 * {@link Channel} and a custom {@link SSLContext} factory, which will be
	 * used to create the context (in turn used to create the {@link SSLEngine},
	 * as a function of the SNI received at the TLS connection start.
	 * <p>
	 * <b>Implementation note:</b><br>
	 * Due to limitations of {@link SSLEngine}, configuring a
	 * {@link ServerTlsChannel} to select the {@link SSLContext} based on the
	 * SNI value implies parsing the first TLS frame (ClientHello) independently
	 * of the SSLEngine.
	 * 
	 * @param underlying
	 *            a reference to the underlying {@link ByteChannel}
	 * @param sslContextFactory
	 *            a function from an optional SNI to the {@link SSLContext} to
	 *            be used
	 * 
	 * @see <a href="https://tools.ietf.org/html/rfc6066#section-3">Server Name
	 *      Indication</a>
	 */
	public static Builder newBuilder(ByteChannel underlying, Function<Optional<String>, SSLContext> sslContextFactory) {
		return new Builder(underlying, sslContextFactory);
	}

	private final ByteChannel underlying;
	private final InternalSslContextFactory internalSslContextFactory;
	private final Function<SSLContext, SSLEngine> engineFactory;
	private final Consumer<SSLSession> sessionInitCallback;
	private final boolean runTasks;
	private final TrackingAllocator plainBufAllocator;
	private final TrackingAllocator encryptedBufAllocator;
	private final boolean releaseBuffers;
	private final boolean waitForCloseConfirmation;

	private final Lock initLock = new ReentrantLock();

	private BufferHolder inEncrypted;

	private volatile boolean sniRead = false;
	private SSLContext sslContext = null;
	private TlsChannelImpl impl = null;

	// @formatter:off
	private ServerTlsChannel(
			ByteChannel underlying, 
			InternalSslContextFactory internalSslContextFactory,
			Function<SSLContext, SSLEngine> engineFactory, 
			Consumer<SSLSession> sessionInitCallback, 
			boolean runTasks,
			BufferAllocator plainBufAllocator,
			BufferAllocator encryptedBufAllocator,
			boolean releaseBuffers,
			boolean waitForCloseConfirmation) {
		this.underlying = underlying;
		this.internalSslContextFactory = internalSslContextFactory;
		this.engineFactory = engineFactory;
		this.sessionInitCallback = sessionInitCallback;
		this.runTasks = runTasks;
		this.plainBufAllocator = new TrackingAllocator(plainBufAllocator);
		this.encryptedBufAllocator = new TrackingAllocator(encryptedBufAllocator);
		this.releaseBuffers = releaseBuffers;
		this.waitForCloseConfirmation = waitForCloseConfirmation;
        inEncrypted = new BufferHolder(
                "inEncrypted",
                Optional.empty(),
                encryptedBufAllocator,
                TlsChannelImpl.buffersInitialSize,
                TlsChannelImpl.maxTlsPacketSize,
                false /* plainData */,
                releaseBuffers);
    }

	// @formatter:on

	@Override
	public ByteChannel getUnderlying() {
		return underlying;
	}

	/**
	 * Return the used {@link SSLContext}.
	 * 
	 * @return if context if present, of null if the TLS connection as not been
	 *         initializer, or the SNI not received yet.
	 */
	public SSLContext getSslContext() {
		return sslContext;
	}

	@Override
	public SSLEngine getSslEngine() {
		return impl == null ? null : impl.engine();
	}

	@Override
	public Consumer<SSLSession> getSessionInitCallback() {
		return sessionInitCallback;
	}

	@Override
	public boolean getRunTasks() {
		return impl.getRunTasks();
	}

	@Override
	public TrackingAllocator getPlainBufferAllocator() {
		return plainBufAllocator;
	}

	@Override
	public TrackingAllocator getEncryptedBufferAllocator() {
		return encryptedBufAllocator;
	}

	@Override
	public long read(ByteBuffer[] dstBuffers, int offset, int length) throws IOException {
		ByteBufferSet dest = new ByteBufferSet(dstBuffers, offset, length);
		TlsChannelImpl.checkReadBuffer(dest);
		if (!sniRead) {
			try {
				initEngine();
			} catch (EofException e) {
				return -1;
			}
		}
		return impl.read(dest);
	}

	@Override
	public long read(ByteBuffer[] dstBuffers) throws IOException {
		return read(dstBuffers, 0, dstBuffers.length);
	}

	@Override
	public int read(ByteBuffer dstBuffer) throws IOException {
		return (int) read(new ByteBuffer[] { dstBuffer });
	}

	@Override
	public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
		ByteBufferSet source = new ByteBufferSet(srcs, offset, length);
		if (!sniRead) {
			try {
				initEngine();
			} catch (EofException e) {
				throw new ClosedChannelException();
			}
		}
		return impl.write(source);
	}

	@Override
	public long write(ByteBuffer[] srcs) throws IOException {
		return write(srcs, 0, srcs.length);
	}

	@Override
	public int write(ByteBuffer srcBuffer) throws IOException {
		return (int) write(new ByteBuffer[] { srcBuffer });
	}

	@Override
	public void renegotiate() throws IOException {
		if (!sniRead) {
			try {
				initEngine();
			} catch (EofException e) {
				throw new ClosedChannelException();
			}
		}
		impl.renegotiate();
	}

	@Override
	public void handshake() throws IOException {
		if (!sniRead) {
			try {
				initEngine();
			} catch (EofException e) {
				throw new ClosedChannelException();
			}
		}
		impl.handshake();
	}

	@Override
	public void close() throws IOException {
		if (impl != null)
			impl.close();
		if (inEncrypted != null)
            inEncrypted.dispose();
		underlying.close();
	}

	@Override
	public boolean isOpen() {
		return underlying.isOpen();
	}

	private void initEngine() throws IOException, EofException {
		initLock.lock();
		try {
			if (!sniRead) {
				sslContext = internalSslContextFactory.getSslContext(() -> getServerNameIndication());
				SSLEngine engine = engineFactory.apply(sslContext);
				impl = new TlsChannelImpl(underlying, underlying, engine, Optional.of(inEncrypted), sessionInitCallback,
						runTasks, plainBufAllocator, encryptedBufAllocator, releaseBuffers, waitForCloseConfirmation);
				inEncrypted = null;
				sniRead = true;
			}
		} finally {
			initLock.unlock();
		}
	}

	private Optional<String> getServerNameIndication() throws IOException, EofException {
		Util.assertTrue(inEncrypted.buffer.position() == 0);
		int recordHeaderSize = readRecordHeaderSize();
		while (inEncrypted.buffer.position() < recordHeaderSize) {
			if (!inEncrypted.buffer.hasRemaining()) {
			    inEncrypted.enlarge();
			}
			TlsChannelImpl.readFromChannel(underlying, inEncrypted.buffer); // IO block
		}
        inEncrypted.buffer.flip();
		Map<Integer, SNIServerName> serverNames = TlsExplorer.explore(inEncrypted.buffer);
        inEncrypted.buffer.compact();
		SNIServerName hostName = serverNames.get(StandardConstants.SNI_HOST_NAME);
		if (hostName != null && hostName instanceof SNIHostName) {
			SNIHostName sniHostName = (SNIHostName) hostName;
			return Optional.of(sniHostName.getAsciiName());
		} else {
			return Optional.empty();
		}
	}

	private int readRecordHeaderSize() throws IOException, EofException {
		while (inEncrypted.buffer.position() < TlsExplorer.RECORD_HEADER_SIZE) {
			if (!inEncrypted.buffer.hasRemaining()) {
                throw new IllegalStateException("inEncrypted too small");
			}
			TlsChannelImpl.readFromChannel(underlying, inEncrypted.buffer); // IO block
		}
        inEncrypted.buffer.flip();
		int recordHeaderSize = TlsExplorer.getRequiredSize(inEncrypted.buffer);
        inEncrypted.buffer.compact();
		return recordHeaderSize;
	}

	@Override
	public boolean shutdown() throws IOException {
		return impl != null && impl.shutdown();
	}

	@Override
	public boolean shutdownReceived() {
		return impl != null && impl.shutdownReceived();
	}

	@Override
	public boolean shutdownSent() {
		return impl != null && impl.shutdownSent();
	}

}
