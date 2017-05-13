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
	boolean waitForCloseConfirmation = false;

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

	/**
	 * Whether to wait for TLS close confirmation when executing a local close
	 * on the channel. If the underlying channel is blocking, setting this to
	 * true will block (potentially until it times out, or indefinitely) the
	 * close operation until the counterpart confirms the close on their side
	 * (sending a close_notifiy packet. Is the underlying channel is
	 * non-blocking, setting this parameter to true is ineffective.
	 * <p>
	 * Setting this value to {@link true} emulates the behavior of
	 * {@link SSLSocket} when used in layered mode (and without autoClose).
	 * <p>
	 * Even when this behavior is enabled, the close operation will not
	 * propagate any {@link IOException} thrown during the TLS close exchange
	 * and just proceed to close the underlying channel. For more control over
	 * the close procedure {@link TlsChannel#shutdown()} should be used.
	 * <p>
	 * Default is to not wait.
	 */
	public T withWaitForCloseConfirmation(boolean waitForCloseConfirmation) {
		this.waitForCloseConfirmation = waitForCloseConfirmation;
		return getThis();
	}

}
