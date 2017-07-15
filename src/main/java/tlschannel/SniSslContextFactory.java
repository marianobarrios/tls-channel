package tlschannel;

import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import java.util.Optional;

/**
 * Producer of a {@link SSLContext} based in an optional {@link SNIServerName}. Implementations of this interface should
 * be supplied to {@link ServerTlsChannel} instances, to be able to pick the correct context (and so the correct
 * certificate) based on the server name provided by the client.
 */
@FunctionalInterface
public interface SniSslContextFactory {
    SSLContext getSslContext(Optional<SNIServerName> sniServerName);
}
