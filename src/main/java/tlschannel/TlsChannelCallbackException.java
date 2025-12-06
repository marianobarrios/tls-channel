package tlschannel;

import javax.net.ssl.SSLException;

/**
 * Thrown during {@link TlsChannel} handshake to indicate that a user-supplied function threw an
 * exception.
 */
public class TlsChannelCallbackException extends SSLException {
    private static final long serialVersionUID = 8491908031320425318L;

    /**
     * Creates an instance of this class.
     *
     * @param message a human-readable message hinting the place of the error
     * @param throwable the original exception
     */
    public TlsChannelCallbackException(String message, Throwable throwable) {
        super(message, throwable);
    }
}
