package tlschannel;

import javax.net.ssl.SSLException;

/**
 * Thrown during {@link TlsChannel} handshake to indicate that a user-supplied function threw an
 * exception.
 */
public class TlsChannelCallbackException extends SSLException {

  public TlsChannelCallbackException(String message, Throwable throwable) {
    super(message, throwable);
  }
}
