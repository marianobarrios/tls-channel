package tlschannel;

import java.io.IOException;

/**
 * Exceptions of the class signal the caller that the operation could not
 * continue because a CPU-intensive operation (usually related to asymmetric
 * cryptography) needs to be executed and the socket is configured to not run
 * tasks. This allows a single thread selector loop to run those tasks in some
 * other threads, taking advantage of having more than one CPU. The operation
 * should be retried once the task supplied in {@link #getTask()} finishes.
 * 
 * Note that this kind of situation doesn't exist in OpenSSL because epoll can
 * be called from multiple threads, but Java {@link Selector}s are single
 * threaded.
 */
public class NeedsTaskException extends IOException {

	private Runnable task;

	public NeedsTaskException(Runnable task) {
		this.task = task;
	}

	public Runnable getTask() {
		return task;
	}

	/**
	 * For efficiency, override this method to do nothing, as this is a
	 * flow-control exception.
	 */
	@Override
	public Throwable fillInStackTrace() {
		return this;
	}

}
