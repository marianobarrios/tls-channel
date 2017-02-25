package tlschannel;

import java.io.IOException;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import java.nio.channels.ByteChannel;
import javax.net.ssl.SSLSession;

import tlschannel.impl.ByteBufferSet;
import tlschannel.impl.TlsChannelImpl;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * A client-side {@link TlsChannel}.
 */
public class ClientTlsChannel implements TlsChannel {

	/**
	 * Builder of {@link ClientTlsChannel}
	 */
	public static class Builder extends TlsChannelBuilder<Builder> {

		private final SSLEngine engine;

		private Builder(ByteChannel underlying, SSLEngine engine) {
			super(underlying);
			this.engine = engine;
		}

		@Override
		Builder getThis() {
			return this;
		}

		public ClientTlsChannel build() {
			return new ClientTlsChannel(underlying, engine, sessionInitCallback, runTasks, plainBufferAllocator,
					encryptedBufferAllocator);
		}

	}

	/**
	 * @param underlying
	 *            a reference to the underlying {@link ByteChannel}
	 */
	public static Builder newBuilder(ByteChannel underlying, SSLEngine engine) {
		return new Builder(underlying, engine);
	}

	private final ByteChannel underlying;
	private final SSLEngine engine;
	private final TlsChannelImpl impl;

	private ClientTlsChannel(ByteChannel underlying, SSLEngine engine, Consumer<SSLSession> sessionInitCallback,
			boolean runTasks, BufferAllocator plainBufferAllocator, BufferAllocator encryptedBufferAllocator) {
		if (!engine.getUseClientMode())
			throw new IllegalArgumentException("SSLEngine must be in client mode");
		this.underlying = underlying;
		this.engine = engine;
		impl = new TlsChannelImpl(underlying, underlying, engine, Optional.empty(), sessionInitCallback, runTasks,
				plainBufferAllocator, encryptedBufferAllocator);
	}

	@Override
	public SSLSession getSession() {
		return engine.getSession();
	}

	@Override
	public ByteChannel getUnderlying() {
		return underlying;
	}

	@Override
	public long read(ByteBuffer[] dstBuffers, int offset, int length) throws IOException {
		ByteBufferSet dest = new ByteBufferSet(dstBuffers, offset, length);
		TlsChannelImpl.checkReadBuffer(dest);
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
	public long write(ByteBuffer[] srcBuffers, int offset, int length) throws IOException {
		ByteBufferSet source = new ByteBufferSet(srcBuffers, offset, length);
		return impl.write(source);
	}

	@Override
	public long write(ByteBuffer[] outs) throws IOException {
		return write(outs, 0, outs.length);
	}

	@Override
	public int write(ByteBuffer srcBuffer) throws IOException {
		return (int) write(new ByteBuffer[] { srcBuffer });
	}

	@Override
	public void renegotiate() throws IOException {
		impl.renegotiate();
	}

	@Override
	public void negotiate() throws IOException {
		impl.negotiateIfNecesary();
	}

	@Override
	public void close() {
		impl.close();
	}

	@Override
	public boolean isOpen() {
		return impl.isOpen();
	}

	@Override
	public boolean getRunTasks() {
		return impl.getRunTasks();
	}

}