package tlschannel;

/**
 * This exception signals the caller that the operation could not continue
 * because bytes need to be written to the network, the socket is non-blocking
 * and there is no buffer space available. The caller should try the operation
 * again, either with the socket in blocking mode of after being sure that bytes
 * can be written immediately (using, for example, a selector). This exception
 * is akin to the SSL_ERROR_WANT_WRITE error code used by OpenSSL.
 */
public class NeedsWriteException extends TlsNonBlockingNecessityException {

}
