package tlschannel;

import java.nio.channels.ByteChannel;
import java.util.function.Consumer;

import javax.net.ssl.SSLSession;

abstract class TlsChannelBuilder<T extends TlsChannelBuilder<T>> {

	final ByteChannel wrapped;
	
	// @formatter:off
	Consumer<SSLSession> sessionInitCallback = session -> {};
	// @formatter:on
	boolean runTasks = true;
	BufferAllocator plainBufferAllocator = new HeapBufferAllocator();
	BufferAllocator encryptedBufferAllocator = new DirectBufferAllocator();

	TlsChannelBuilder(ByteChannel wrapped) {
		this.wrapped = wrapped;
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
	 * Set which buffer to use for decrypted data. By default a
	 * {@link HeapBufferAllocator} is used.
	 */
	public T withPlainBufferAllocator(BufferAllocator bufferAllocator) {
		this.plainBufferAllocator = bufferAllocator;
		return getThis();
	}

	/**
	 * Set which buffer to use for encrypted data. By default a
	 * {@link DirectBufferAllocator} is used, as this data is usually read from
	 * or written to native sockets.
	 */
	public T withEncryptedBufferAllocator(BufferAllocator bufferAllocator) {
		this.encryptedBufferAllocator = bufferAllocator;
		return getThis();
	}

	public T withSessionInitCallback(Consumer<SSLSession> sessionInitCallback) {
		this.sessionInitCallback = sessionInitCallback;
		return getThis();
	}

}
