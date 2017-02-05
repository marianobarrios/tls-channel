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
import tlschannel.impl.TlsSocketChannelImpl;
import tlschannel.util.Util;

public class TlsServerSocketChannel implements TlsSocketChannel {

	private static SSLEngine defaultSSLEngineFactory(SSLContext sslContext) {
		SSLEngine engine = sslContext.createSSLEngine();
		engine.setUseClientMode(false);
		return engine;
	}

	public static class Builder {

		private final ByteChannel wrapped;
		private final Function<Optional<String>, SSLContext> contextFactory;
		private Function<SSLContext, SSLEngine> engineFactory = TlsServerSocketChannel::defaultSSLEngineFactory;
		// @formatter:off
		private Consumer<SSLSession> sessionInitCallback = session -> {};
		// @formatter:on
		private boolean runTasks = true;
		private BufferAllocator plainBufferAllocator = new HeapBufferAllocator();
		private BufferAllocator encryptedBufferAllocator = new DirectBufferAllocator();

		public Builder(ByteChannel wrapped, SSLContext context) {
			this.wrapped = wrapped;
			this.contextFactory = name -> context;
		}

		public Builder(ByteChannel wrapped, Function<Optional<String>, SSLContext> contextFactory) {
			this.wrapped = wrapped;
			this.contextFactory = contextFactory;
		}

		public Builder withEngineFactory(Function<SSLContext, SSLEngine> engineFactory) {
			this.engineFactory = engineFactory;
			return this;
		}

		public Builder withSessionInitCallback(Consumer<SSLSession> sessionInitCallback) {
			this.sessionInitCallback = sessionInitCallback;
			return this;
		}

		/**
		 * Whether CPU-intensive tasks are run or not. Default is to do run
		 * them. If setting this {@link false}, the calling code should be
		 * prepared to handle {@link NeedsTaskException}}
		 */
		public Builder withRunTasks(boolean runTasks) {
			this.runTasks = runTasks;
			return this;
		}

		/**
		 * Set which buffer to use for decrypted data. By default a
		 * {@link HeapBufferAllocator} is used.
		 */
		public Builder withPlainBufferAllocator(BufferAllocator bufferAllocator) {
			this.plainBufferAllocator = bufferAllocator;
			return this;
		}

		/**
		 * Set which buffer to use for encrypted data. By default a
		 * {@link DirectBufferAllocator} is used, as this data is usually read
		 * from or written to native sockets.
		 */
		public Builder withEncryptedBufferAllocator(BufferAllocator bufferAllocator) {
			this.encryptedBufferAllocator = bufferAllocator;
			return this;
		}

		public TlsServerSocketChannel build() {
			return new TlsServerSocketChannel(wrapped, contextFactory, engineFactory, sessionInitCallback, runTasks,
					plainBufferAllocator, encryptedBufferAllocator);
		}

	}

	private final static int maxTlsPacketSize = 16 * 1024;

	private final ByteChannel wrapped;
	private final Function<Optional<String>, SSLContext> contextFactory;
	private final Function<SSLContext, SSLEngine> engineFactory;
	private final Consumer<SSLSession> sessionInitCallback;
	private final boolean runTasks;
	private final BufferAllocator plainBufferAllocator;
	private final BufferAllocator encryptedBufferAllocator;

	private final Lock initLock = new ReentrantLock();

	private ByteBuffer buffer;

	private volatile boolean sniRead = false;
	private TlsSocketChannelImpl impl = null;

	// @formatter:off
	private TlsServerSocketChannel(
			ByteChannel wrapped, 
			Function<Optional<String>, SSLContext> contextFactory,
			Function<SSLContext, SSLEngine> engineFactory, 
			Consumer<SSLSession> sessionInitCallback, 
			boolean runTasks,
			BufferAllocator plainBufferAllocator,
			BufferAllocator encryptedBufferAllocator) {
		this.wrapped = wrapped;
		this.contextFactory = contextFactory;
		this.engineFactory = engineFactory;
		this.sessionInitCallback = sessionInitCallback;
		this.runTasks = runTasks;
		this.plainBufferAllocator = plainBufferAllocator;
		this.encryptedBufferAllocator = encryptedBufferAllocator;
		buffer = encryptedBufferAllocator.allocate(TlsSocketChannelImpl.buffersInitialSize);
	}
	
	// @formatter:on

	@Override
	public ByteChannel getWrapped() {
		return wrapped;
	}

	@Override
	public long read(ByteBuffer[] dstBuffers, int offset, int length) throws IOException {
		ByteBufferSet dest = new ByteBufferSet(dstBuffers, offset, length);
		TlsSocketChannelImpl.checkReadBuffer(dest);
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
		Util.closeChannel(wrapped);
	}

	@Override
	public boolean isOpen() {
		return wrapped.isOpen();
	}

	@Override
	public SSLSession getSession() {
		if (impl == null)
			return null;
		else
			return impl.engine().getSession();
	}

	private void initEngine() throws IOException {
		initLock.lock();
		try {
			if (!sniRead) {
				// IO block
				Optional<String> nameOpt = getServerNameIndication(wrapped);
				// call client code
				SSLContext sslContext = contextFactory.apply(nameOpt);
				SSLEngine engine = engineFactory.apply(sslContext);
				impl = new TlsSocketChannelImpl(wrapped, wrapped, engine, Optional.of(buffer), sessionInitCallback,
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
				buffer = Util.enlarge(encryptedBufferAllocator, buffer, "inEncryptedPreFetch", maxTlsPacketSize);
			}
			TlsSocketChannelImpl.readFromNetwork(channel, buffer); // IO block
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
						TlsExplorer.RECORD_HEADER_SIZE);
			}
			TlsSocketChannelImpl.readFromNetwork(channel, buffer); // IO block
		}
		buffer.flip();
		int recordHeaderSize = TlsExplorer.getRequiredSize(buffer);
		buffer.compact();
		return recordHeaderSize;
	}

	@Override
	public boolean getRunTasks() {
		return impl.getRunTasks();
	}

}
