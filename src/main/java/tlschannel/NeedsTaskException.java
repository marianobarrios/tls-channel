package tlschannel;

/**
 * This exception signals the caller that the operation could not continue because a CPU-intensive
 * operation (typically a TLS handshaking) needs to be executed and the {@link TlsChannel} is
 * configured to not run tasks. This allows the application to run these tasks in some other
 * threads, in order to not slow the selection loop. The method that threw the exception should be
 * retried once the task supplied by {@link #getTask()} is executed and finished.
 *
 * <p>This exception is akin to the SSL_ERROR_WANT_ASYNC error code used by OpenSSL (but note that
 * in OpenSSL, the task is executed by the library, while with the {@link TlsChannel}, the calling
 * code is responsible for the execution).
 *
 * @see <a href="https://www.openssl.org/docs/man1.1.0/ssl/SSL_get_error.html">OpenSSL error
 *     documentation</a>
 */
public class NeedsTaskException extends TlsChannelFlowControlException {

  private static final long serialVersionUID = -936451835836926915L;
  private final Runnable task;

  public NeedsTaskException(Runnable task) {
    this.task = task;
  }

  public Runnable getTask() {
    return task;
  }
}
