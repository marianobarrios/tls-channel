package tlschannel.example;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLContext;
import tlschannel.ServerTlsChannel;
import tlschannel.TlsChannel;

/**
 * Server example. Accepts one connection and echos bytes sent by the client into standard output.
 *
 * <p>To test, use: <code>
 * openssl s_client -connect localhost:10000
 * </code>
 */
public class SimpleBlockingServer {

    private static final Charset utf8 = StandardCharsets.UTF_8;

    public static void main(String[] args) throws IOException, GeneralSecurityException {

        // initialize the SSLContext, a configuration holder, reusable object
        SSLContext sslContext = ContextFactory.authenticatedContext("TLSv1.2");

        // connect server socket channel normally
        try (ServerSocketChannel serverSocket = ServerSocketChannel.open()) {
            serverSocket.socket().bind(new InetSocketAddress(10000));

            // accept raw connections normally
            System.out.println("Waiting for connection...");
            try (SocketChannel rawChannel = serverSocket.accept()) {

                // create TlsChannel builder, combining the raw channel and the SSLEngine, using minimal
                // options
                ServerTlsChannel.Builder builder = ServerTlsChannel.newBuilder(rawChannel, sslContext);

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
