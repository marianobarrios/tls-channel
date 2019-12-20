package tlschannel;

import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import java.util.Optional;

/**
 * Factory for {@link SSLContext}s, based in an optional {@link SNIServerName}. Implementations of
 * this interface are supplied to {@link ServerTlsChannel} instances, to select the correct context
 * (and so the correct certificate) based on the server name provided by the client.
 */
@FunctionalInterface
public interface SniSslContextFactory {

  /**
   * Return a proper {@link SSLContext}.
   *
   * @param sniServerName an optional {@link SNIServerName}; an empty value means that the client
   *     did not send and SNI value.
   * @return the chosen context, or an empty value, indicating that no context is supplied and the
   *     connection should be aborted.
   */
  Optional<SSLContext> getSslContext(Optional<SNIServerName> sniServerName);
}
