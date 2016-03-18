package tlschannel;

/**
 * This exception signals the caller that the operation could not continue
 * because bytes need to be read from the network, the socket is non-blocking
 * and there are no bytes available. The caller should try the operation again,
 * either with the socket in blocking mode of after being sure that bytes are
 * ready (using, for example, a selector). This exception is akin to the
 * SSL_ERROR_WANT_READ error code used by OpenSSL.
 */
public class NeedsReadException extends TlsNonBlockingNecessityException {

}
