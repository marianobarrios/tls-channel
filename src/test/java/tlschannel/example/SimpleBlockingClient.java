package tlschannel.example;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import javax.net.ssl.SSLContext;
import tlschannel.ClientTlsChannel;
import tlschannel.TlsChannel;

/** Client example. Connects to a public TLS reporting service. */
public class SimpleBlockingClient {

    private static final Charset utf8 = StandardCharsets.UTF_8;

    public static final String domain = "tcpbin.com";
    public static final int port = 4243;
    public static final String message = "the message\n";

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {

        // initialize the SSLContext, a configuration holder, reusable object
        SSLContext sslContext = SSLContext.getDefault();

        // connect raw socket channel normally
        try (SocketChannel rawChannel = SocketChannel.open()) {
            rawChannel.connect(new InetSocketAddress(domain, port));

            // create TlsChannel builder, combining the raw channel and the SSLEngine, using minimal
            // options
            ClientTlsChannel.Builder builder = ClientTlsChannel.newBuilder(rawChannel, sslContext);

            // instantiate TlsChannel
            try (TlsChannel tlsChannel = builder.build()) {

                // do HTTP interaction and print result
                tlsChannel.write(ByteBuffer.wrap(message.getBytes(StandardCharsets.US_ASCII)));
                ByteBuffer res = ByteBuffer.allocate(10000);

                // being HTTP 1.0, the server will just close the connection at the end
                while (tlsChannel.read(res) != -1) {
                    // empty
                }
                res.flip();
                System.out.println(utf8.decode(res));
            }
        }
    }
}
