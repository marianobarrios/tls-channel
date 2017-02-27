package tlschannel;

import java.nio.channels.ByteChannel;
import java.util.function.Consumer;

import javax.net.ssl.SSLSession;

public abstract class TlsChannelBuilder<T extends TlsChannelBuilder<T>> {

	final ByteChannel underlying;

	// @formatter:off
	Consumer<SSLSession> sessionInitCallback = session -> {};
	// @formatter:on
	boolean runTasks = true;
	BufferAllocator plainBufferAllocator = new HeapBufferAllocator();
	BufferAllocator encryptedBufferAllocator = new DirectBufferAllocator();

	TlsChannelBuilder(ByteChannel underlying) {
		this.underlying = underlying;
	}

	abstract T getThis();

	/**
	 * Whether CPU-intensive tasks are run or not. Default is to do run them. If
	 * setting this {@link false}, the calling code should be prepared to handle
	 * {@link NeedsTaskException}}
	 */
	public T withRunTasks(boolean runTasks) {
		this.runTasks = runTasks;
		return getThis();
	}

	/**
	 * Set the {@link BufferAllocator} to use for unencrypted data. By default a
	 * {@link HeapBufferAllocator} is used, as this buffers are used to
	 * supplement user-supplied ones when dealing with too big a TLS record,
	 * that is, they operate entirely inside the JVM.
	 */
	public T withPlainBufferAllocator(BufferAllocator bufferAllocator) {
		this.plainBufferAllocator = bufferAllocator;
		return getThis();
	}

	/**
	 * Set the {@link BufferAllocator} to use for encrypted data. By default a
	 * {@link DirectBufferAllocator} is used, as this data is read from or
	 * written to native sockets.
	 */
	public T withEncryptedBufferAllocator(BufferAllocator bufferAllocator) {
		this.encryptedBufferAllocator = bufferAllocator;
		return getThis();
	}

	/**
	 * Register a callback function to be executed when the TLS session is
	 * established (or re-established). The supplied function will run in the
	 * same thread as the rest of the handshake, so it should ideally run as
	 * fast as possible.
	 */
	public T withSessionInitCallback(Consumer<SSLSession> sessionInitCallback) {
		this.sessionInitCallback = sessionInitCallback;
		return getThis();
	}

}
