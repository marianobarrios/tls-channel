package tlschannel.example;

import tlschannel.ServerTlsChannel;
import tlschannel.SniSslContextFactory;
import tlschannel.TlsChannel;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Optional;

/**
 * <p> Server examples. Accepts one connection and echos bytes sent by the client into standard outout.</p>
 * <p> To test, use: </p>
 * <code>
 * openssl s_client -connect localhost:10000 -servername domain.com -tls1
 * </code>
 */
public class SniBlockingServer {

    private static final Charset utf8 = StandardCharsets.UTF_8;

    public static void main(String[] args) throws IOException, GeneralSecurityException {

        // initialize the SSLContext, a configuration holder, reusable object
        SSLContext sslContext = SimpleBlockingServer.authenticatedContext("TLSv1.2");

        /*
         * Set the SSLContext factory with a lambda expression. In this case we reject the connection in all cases
         * except when the supplied domain matches exacting, in which case we just return our default context. A real
         * implementation would have more than one context to return according to the supplied name.
         */
        SniSslContextFactory exampleSslContextFactory = (Optional<SNIServerName> sniServerName) -> {
            if (!sniServerName.isPresent()) {
                return Optional.empty();
            }
            SNIServerName name = sniServerName.get();
            if (!(name instanceof SNIHostName)) {
                return Optional.empty();
            }
            SNIHostName hostName = (SNIHostName) name;
            if (hostName.getAsciiName().equals("domain.com")) {
                return Optional.of(sslContext);
            } else {
                return Optional.empty();
            }
        };

        // connect server socket channel normally
        try (ServerSocketChannel serverSocket = ServerSocketChannel.open()) {
            serverSocket.socket().bind(new InetSocketAddress(10000));

            // accept raw connections normally
            System.out.println("Waiting for connection...");
            try (SocketChannel rawChannel = serverSocket.accept()) {

                // create TlsChannel builder, combining the raw channel and the defined SSLContext factory
                ServerTlsChannel.Builder builder = ServerTlsChannel.newBuilder(rawChannel, exampleSslContextFactory);

                // instantiate TlsChannel
                try (TlsChannel tlsChannel = builder.build()) {

                    // write to stdout all data sent by the client
                    ByteBuffer res = ByteBuffer.allocate(10000);
                    while (tlsChannel.read(res) != -1) {
                        res.flip();
                        System.out.print(utf8.decode(res).toString());
                        res.compact();
                    }
                }
            }
        }
    }

}
