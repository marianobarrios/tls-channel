package tlschannel;

import java.io.IOException;

/**
 * Exceptions of the class signal the caller that the operation could not
 * continue because the socket is non-blocking and the read or write operation
 * would block (i.e. the socket is not ready). The caller should try the
 * operation again, either with the socket in blocking mode of after being sure
 * that the operation can be completed (using, for example, a selector). This
 * mechanism is akin to the SSL_ERROR_WANT_WRITE and SSL_ERROR_WANT_READ error
 * codes used by OpenSSL.
 */
public class TlsNonBlockingNecessityException extends IOException {

}
