package tlschannel;

import javax.net.ssl.SSLException;

/**
 * Thrown during {@link TlsChannel} handshake to indicate that a user-supplied function threw an
 * exception.
 */
public class TlsChannelCallbackException extends SSLException {
    private static final long serialVersionUID = 8491908031320425318L;

    public TlsChannelCallbackException(String message, Throwable throwable) {
        super(message, throwable);
    }
}
