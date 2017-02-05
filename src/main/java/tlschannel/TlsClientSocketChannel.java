package tlschannel;

import java.io.IOException;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import java.nio.channels.ByteChannel;
import javax.net.ssl.SSLSession;

import tlschannel.impl.ByteBufferSet;
import tlschannel.impl.TlsSocketChannelImpl;

import java.util.Optional;
import java.util.function.Consumer;

public class TlsClientSocketChannel implements TlsSocketChannel {

	public static class Builder {

		private final ByteChannel wrapped;
		private final SSLEngine engine;
		// @formatter:off
		private Consumer<SSLSession> sessionInitCallback = session -> {};
		// @formatter:on
		private boolean runTasks = true;
		private BufferAllocator plainBufferAllocator = new HeapBufferAllocator();
		private BufferAllocator encryptedBufferAllocator = new DirectBufferAllocator();

		public Builder(ByteChannel wrapped, SSLEngine engine) {
			this.wrapped = wrapped;
			this.engine = engine;
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
		 * {@link DirectBufferAllocator} is used.
		 */
		public Builder withEncryptedBufferAllocator(BufferAllocator bufferAllocator) {
			this.encryptedBufferAllocator = bufferAllocator;
			return this;
		}

		public TlsClientSocketChannel build() {
			return new TlsClientSocketChannel(wrapped, engine, sessionInitCallback, runTasks, plainBufferAllocator,
					encryptedBufferAllocator);
		}
	}

	private final ByteChannel wrapped;
	private final SSLEngine engine;
	private final TlsSocketChannelImpl impl;

	private TlsClientSocketChannel(ByteChannel wrapped, SSLEngine engine, Consumer<SSLSession> sessionInitCallback,
			boolean runTasks, BufferAllocator plainBufferAllocator, BufferAllocator encryptedBufferAllocator) {
		if (!engine.getUseClientMode())
			throw new IllegalArgumentException("SSLEngine must be in client mode");
		this.wrapped = wrapped;
		this.engine = engine;
		impl = new TlsSocketChannelImpl(wrapped, wrapped, engine, Optional.empty(), sessionInitCallback, runTasks,
				plainBufferAllocator, encryptedBufferAllocator);
	}

	@Override
	public SSLSession getSession() {
		return engine.getSession();
	}

	@Override
	public ByteChannel getWrapped() {
		return wrapped;
	}

	@Override
	public long read(ByteBuffer[] dstBuffers, int offset, int length) throws IOException {
		ByteBufferSet dest = new ByteBufferSet(dstBuffers, offset, length);
		TlsSocketChannelImpl.checkReadBuffer(dest);
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