package tlschannel;

import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

/**
 * This exception signals the caller that the operation cannot continue because
 * bytes need to be write to the underlying {@link ByteChannel}, the channel is
 * non-blocking and there are no buffer space available. The caller should try
 * the operation again, either with the channel in blocking mode of after
 * ensuring that buffer space exists.
 * <p>
 * For {@link SocketChannel}s, a {@link Selector} can be used to find out when
 * the method should be retried.
 * <p>
 * Caveat: Any {@link TlsChannel} I/O method can throw this exception. In
 * particular, <code>read</code> may want to write data. This is because TLS
 * handshakes may occur at any time (initiated by either the client or the
 * server).
 * <p>
 * This exception is akin to the SSL_ERROR_WANT_WRITE error code used by
 * OpenSSL.
 * 
 * @see <a href="https://www.openssl.org/docs/man1.1.0/ssl/SSL_get_error.html">
 *      OpenSSL error documentation</a>
 */

public class NeedsWriteException extends TlsNonBlockingNecessityException {

}
