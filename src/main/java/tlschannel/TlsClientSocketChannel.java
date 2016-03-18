package tlschannel;

import java.io.IOException;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import java.nio.channels.ByteChannel;
import javax.net.ssl.SSLSession;
import java.util.function.Consumer;

public class TlsClientSocketChannel implements TlsSocketChannel {

	private final ByteChannel wrapped;
	private final SSLEngine engine;
	private final TlsSocketChannelImpl impl;
	private final ByteBuffer inBuffer = ByteBuffer.allocate(TlsSocketChannelImpl.tlsMaxRecordSize);
	
	public TlsClientSocketChannel(ByteChannel wrapped, SSLEngine engine, Consumer<SSLSession> sessionInitCallback) {
		if (!engine.getUseClientMode())
			throw new IllegalArgumentException("SSLEngine must be in client mode");
		this.wrapped = wrapped;
		this.engine = engine;
		impl = new TlsSocketChannelImpl(wrapped, wrapped, engine, inBuffer, sessionInitCallback);
	}

	public TlsClientSocketChannel(ByteChannel wrapped, SSLEngine engine) {
		// @formatter:off
		this(wrapped, engine, session -> {});
		// @formatter:on
	}

	public SSLSession getSession() {
		return engine.getSession();
	}

	@Override
	public ByteChannel getWrapped() {
		return wrapped;
	}

	public int read(ByteBuffer in) throws IOException {
		TlsSocketChannelImpl.checkReadBuffer(in);
		return impl.read(in);
	}

	public int write(ByteBuffer out) throws IOException {
		TlsSocketChannelImpl.checkWriteBuffer(out);
		return impl.write(out);
	}

	public void renegotiate() throws IOException {
		impl.renegotiate();
	}

	public void doPassiveHandshake() throws IOException {
		impl.doPassiveHandshake();
	}

	public void doHandshake() throws IOException {
		impl.doHandshake();
	}

	public void close() {
		impl.close();
	}

	public boolean isOpen() {
		return impl.isOpen();
	}

}