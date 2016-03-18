package tlschannel;

import java.io.EOFException;
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
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.StandardConstants;

public class TlsServerSocketChannel implements TlsSocketChannel {

	private final ByteChannel wrapped;
	private final Function<Optional<String>, SSLContext> contextFactory;
	private final Consumer<SSLEngine> engineConfigurator; // engine => ()
	private final Consumer<SSLSession> sessionInitCallback; // session => ()

	private final Lock initLock = new ReentrantLock();
	private final ByteBuffer buffer = ByteBuffer.allocate(TlsSocketChannelImpl.tlsMaxRecordSize);

	private volatile boolean sniRead = false;
	private TlsSocketChannelImpl impl = null;

	// @formatter:off
	public TlsServerSocketChannel(
			ByteChannel wrapped, 
			Function<Optional<String>, SSLContext> contextFactory,
			Consumer<SSLEngine> engineConfigurator, 
			Consumer<SSLSession> sessionInitCallback) {
		this.wrapped = wrapped;
		this.contextFactory = contextFactory;
		this.engineConfigurator = engineConfigurator;
		this.sessionInitCallback = sessionInitCallback;
	}
	// @formatter:on

	// @formatter:off
	public TlsServerSocketChannel(
			ByteChannel wrapped, 
			Function<Optional<String>, SSLContext> contextFactory, 
			Consumer<SSLEngine> engineConfigurator) {
		this(wrapped, contextFactory, engineConfigurator, session -> {});
	}
	// @formatter:on

	public TlsServerSocketChannel(ByteChannel wrapped, Function<Optional<String>, SSLContext> contextFactory) {
		// @formatter:off
		this(wrapped, contextFactory, engine -> {}, session -> {});
		// @formatter:on
	}
	
	// @formatter:off
	public TlsServerSocketChannel(
			ByteChannel wrapped, 
			SSLContext sslContext, 
			Consumer<SSLEngine> engineConfigurator, 
			Consumer<SSLSession> sessionInitCallback) {
		this(wrapped, name -> sslContext, engineConfigurator, sessionInitCallback);
	}
	// @formatter:on
	
	public TlsServerSocketChannel(ByteChannel wrapped, SSLContext sslContext, Consumer<SSLEngine> engineConfigurator) {
		// @formatter:off
		this(wrapped, name -> sslContext, engineConfigurator, session -> {});
		// @formatter:on
	}

	public TlsServerSocketChannel(ByteChannel wrapped, SSLContext sslContext) {
		// @formatter:off
		this(wrapped, name -> sslContext, engine -> {}, session -> {});
		// @formatter:on
	}

	@Override
	public ByteChannel getWrapped() {
		return wrapped;
	}

	@Override
	public int read(ByteBuffer dstBuffer) throws IOException {
		TlsSocketChannelImpl.checkReadBuffer(dstBuffer);
		if (!sniRead)
			initEngine();
		return impl.read(dstBuffer);
	}

	@Override
	public int write(ByteBuffer srcBuffer) throws IOException {
		if (!sniRead)
			initEngine();
		return impl.write(srcBuffer);
	}

	@Override
	public void renegotiate() throws IOException {
		if (!sniRead)
			initEngine();
		impl.renegotiate();
	}

	@Override
	public void doPassiveHandshake() throws IOException {
		if (!sniRead)
			initEngine();
		impl.doPassiveHandshake();
	}

	@Override
	public void doHandshake() throws IOException {
		if (!sniRead)
			initEngine();
		impl.doHandshake();
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
				Optional<String> nameOpt = getServerNameIndication(wrapped, buffer);
				// call client code
				SSLContext sslContext = contextFactory.apply(nameOpt);
				SSLEngine engine = sslContext.createSSLEngine();
				engineConfigurator.accept(engine); // call client code
				engine.setUseClientMode(false);
				impl = new TlsSocketChannelImpl(wrapped, wrapped, engine, buffer, sessionInitCallback);
				sniRead = true;
			}
		} finally {
			initLock.unlock();
		}
	}

	private Optional<Integer> recordHeaderSize = Optional.empty();

	private Optional<String> getServerNameIndication(ReadableByteChannel channel, ByteBuffer buffer)
			throws IOException {
		if (!recordHeaderSize.isPresent())
			recordHeaderSize = Optional.of(readRecordHeaderSize(channel, buffer));
		while (buffer.position() < recordHeaderSize.get()) {
			readFromNetwork(channel, buffer); // IO block
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

	private int readRecordHeaderSize(ReadableByteChannel channel, ByteBuffer buffer) throws IOException {
		while (buffer.position() < TlsExplorer.RECORD_HEADER_SIZE) {
			readFromNetwork(channel, buffer); // IO block
		}
		buffer.flip();
		int recordHeaderSize = TlsExplorer.getRequiredSize(buffer);
		if (recordHeaderSize > TlsSocketChannelImpl.tlsMaxRecordSize)
			throw new SSLException("record size too big: " + recordHeaderSize);
		buffer.compact();
		return recordHeaderSize;
	}

	private int readFromNetwork(ReadableByteChannel channel, ByteBuffer buffer) throws IOException {
		int n = channel.read(buffer); // IO block
		if (n == -1)
			throw new EOFException();
		if (n == 0) {
			// This can only happen if the socket is non-blocking
			throw new NeedsReadException();
		}
		return n;
	}

}
