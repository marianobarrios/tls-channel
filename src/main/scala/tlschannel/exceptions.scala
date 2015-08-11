package tlschannel

import java.io.IOException

/**
 * Exceptions of the class signal the caller that the operation could not continue because the socket is non-blocking 
 * and the read or write operation would block (i.e. the socket is not ready). The caller should try the operation 
 * again, either with the socket in blocking mode of after being sure that the operation can be completed (using, for 
 * example, a selector). This mechanism is akin to the SSL_ERROR_WANT_WRITE and SSL_ERROR_WANT_READ error codes used by 
 * OpenSSL.
 */
class TlsNonBlockingNecessityException extends IOException

/**
 * This exception signals the caller that the operation could not continue because bytes need to be written to the 
 * network, the socket is non-blocking and there is no buffer space available. The caller should try the operation 
 * again, either with the socket in blocking mode of after being sure that bytes can be written immediately (using, for 
 * example, a selector). This exception is akin to the SSL_ERROR_WANT_WRITE error code used by OpenSSL.
 */
class NeedsWriteException extends TlsNonBlockingNecessityException

/**
 * This exception signals the caller that the operation could not continue because bytes need to be read from the 
 * network, the socket is non-blocking and there are no bytes available. The caller should try the operation again, 
 * either with the socket in blocking mode of after being sure that bytes are ready (using, for example, a selector). 
 * This exception is akin to the SSL_ERROR_WANT_READ error code used by OpenSSL.
 */
class NeedsReadException extends TlsNonBlockingNecessityException
