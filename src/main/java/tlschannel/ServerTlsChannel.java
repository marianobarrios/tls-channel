package tlschannel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ReadableByteChannel;
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

import tlschannel.impl.ByteBufferSet;
import tlschannel.impl.TlsExplorer;
import tlschannel.impl.TlsChannelImpl;
import tlschannel.util.Util;

/**
 * A server-side {@link TlsChannel}.
 */
public class ServerTlsChannel implements TlsChannel {

	private static SSLEngine defaultSSLEngineFactory(SSLContext sslContext) {
		SSLEngine engine = sslContext.createSSLEngine();
		engine.setUseClientMode(false);
		return engine;
	}

	/**
	 * Builder of {@link ServerTlsChannel}
	 */
	public static class Builder extends TlsChannelBuilder<Builder> {

		private final Function<Optional<String>, SSLContext> sslContextFactory;
		private Function<SSLContext, SSLEngine> sslEngineFactory = ServerTlsChannel::defaultSSLEngineFactory;

		private Builder(ByteChannel underlying, SSLContext sslContext) {
			super(underlying);
			this.sslContextFactory = name -> sslContext;
		}

		private Builder(ByteChannel wrapped, Function<Optional<String>, SSLContext> sslContextFactory) {
			super(wrapped);
			this.sslContextFactory = sslContextFactory;
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
			return new ServerTlsChannel(underlying, sslContextFactory, sslEngineFactory, sessionInitCallback, runTasks,
					plainBufferAllocator, encryptedBufferAllocator);
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
	 * used to create the context (in turn used to create the {@link SSLEngine}
	 * ), as a function of the SNI received at the TLS connection start.
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

	private final static int maxTlsPacketSize = 16 * 1024;

	private final ByteChannel underlying;
	private final Function<Optional<String>, SSLContext> sslContextFactory;
	private final Function<SSLContext, SSLEngine> engineFactory;
	private final Consumer<SSLSession> sessionInitCallback;
	private final boolean runTasks;
	private final BufferAllocator plainBufferAllocator;
	private final BufferAllocator encryptedBufferAllocator;

	private final Lock initLock = new ReentrantLock();

	private ByteBuffer buffer;

	private volatile boolean sniRead = false;
	private SSLContext sslContext = null;
	private TlsChannelImpl impl = null;

	// @formatter:off
	private ServerTlsChannel(
			ByteChannel underlying, 
			Function<Optional<String>, SSLContext> sslContextFactory,
			Function<SSLContext, SSLEngine> engineFactory, 
			Consumer<SSLSession> sessionInitCallback, 
			boolean runTasks,
			BufferAllocator plainBufferAllocator,
			BufferAllocator encryptedBufferAllocator) {
		this.underlying = underlying;
		this.sslContextFactory = sslContextFactory;
		this.engineFactory = engineFactory;
		this.sessionInitCallback = sessionInitCallback;
		this.runTasks = runTasks;
		this.plainBufferAllocator = plainBufferAllocator;
		this.encryptedBufferAllocator = encryptedBufferAllocator;
		buffer = encryptedBufferAllocator.allocate(TlsChannelImpl.buffersInitialSize);
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
	public BufferAllocator getPlainBufferAllocator() {
		return plainBufferAllocator;
	}

	@Override
	public BufferAllocator getEncryptedBufferAllocator() {
		return encryptedBufferAllocator;
	}

	@Override
	public long read(ByteBuffer[] dstBuffers, int offset, int length) throws IOException {
		ByteBufferSet dest = new ByteBufferSet(dstBuffers, offset, length);
		TlsChannelImpl.checkReadBuffer(dest);
		if (!sniRead)
			initEngine();
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
		if (!sniRead)
			initEngine();
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
		if (!sniRead)
			initEngine();
		impl.renegotiate();
	}

	@Override
	public void negotiate() throws IOException {
		if (!sniRead)
			initEngine();
		impl.negotiateIfNecesary();
	}

	@Override
	public void close() {
		if (impl != null)
			impl.close();
		Util.closeChannel(underlying);
	}

	@Override
	public boolean isOpen() {
		return underlying.isOpen();
	}

	private void initEngine() throws IOException {
		initLock.lock();
		try {
			if (!sniRead) {
				// IO block
				Optional<String> nameOpt = getServerNameIndication(underlying);
				// call client code
				sslContext = sslContextFactory.apply(nameOpt);
				SSLEngine engine = engineFactory.apply(sslContext);
				impl = new TlsChannelImpl(underlying, underlying, engine, Optional.of(buffer), sessionInitCallback,
						runTasks, plainBufferAllocator, encryptedBufferAllocator);
				sniRead = true;
			}
		} finally {
			initLock.unlock();
		}
	}

	private Optional<String> getServerNameIndication(ReadableByteChannel channel) throws IOException {
		Util.assertTrue(buffer.position() == 0);
		int recordHeaderSize = readRecordHeaderSize(channel);
		while (buffer.position() < recordHeaderSize) {
			if (!buffer.hasRemaining()) {
				buffer = Util.enlarge(encryptedBufferAllocator, buffer, "inEncryptedPreFetch", maxTlsPacketSize,
						false /* zero */);
			}
			TlsChannelImpl.readFromNetwork(channel, buffer); // IO block
		}
		buffer.flip();
		Map<Integer, SNIServerName> serverNames = TlsExplorer.explore(buffer);
		buffer.compact();
		SNIServerName hostName = serverNames.get(StandardConstants.SNI_HOST_NAME);
		if (hostName != null && hostName instanceof SNIHostName) {
			SNIHostName sniHostName = (SNIHostName) hostName;
			return Optional.of(sniHostName.getAsciiName());
		} else {
			return Optional.empty();
		}
	}

	private int readRecordHeaderSize(ReadableByteChannel channel) throws IOException {
		while (buffer.position() < TlsExplorer.RECORD_HEADER_SIZE) {
			if (!buffer.hasRemaining()) {
				buffer = Util.enlarge(encryptedBufferAllocator, buffer, "inEncryptedPreFetch",
						TlsExplorer.RECORD_HEADER_SIZE, false /* zero */);
			}
			TlsChannelImpl.readFromNetwork(channel, buffer); // IO block
		}
		buffer.flip();
		int recordHeaderSize = TlsExplorer.getRequiredSize(buffer);
		buffer.compact();
		return recordHeaderSize;
	}

}
