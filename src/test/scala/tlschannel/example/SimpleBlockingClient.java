package tlschannel.example;

import tlschannel.ClientTlsChannel;
import tlschannel.TlsChannel;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;

public class SimpleBlockingClient {

    public static final String domain = "www.howsmyssl.com";
    public static final String httpLine =
            "GET https://www.howsmyssl.com/a/check HTTP/1.0\nHost: www.howsmyssl.com\n\n";

    public static void main(String[] args) throws IOException, NoSuchAlgorithmException {

        // initialize the SSLContext, a configuration holder, reusable object
        SSLContext sslContext = SSLContext.getDefault();

        // connect raw socket channel normally
        try (SocketChannel rawChannel = SocketChannel.open()) {
            rawChannel.connect(new InetSocketAddress(domain, 443));

            // instantiate SSLEngine and set in client mode
            SSLEngine sslEngine = sslContext.createSSLEngine();
            sslEngine.setUseClientMode(true);

            // create TlsChannel builder, combining the raw channel and the SSLEngine, using minimal options
            ClientTlsChannel.Builder builder = ClientTlsChannel.newBuilder(rawChannel, sslEngine);

            // instantiate TlsChannel
            try (TlsChannel tlsChannel = builder.build()) {

                // do HTTP interaction and print result
                tlsChannel.write(ByteBuffer.wrap(httpLine.getBytes()));
                ByteBuffer res = ByteBuffer.allocate(10000);
                // being HTTP 1.0, the server will just close the connection at the end
                while (tlsChannel.read(res) != -1)
                    ;
                res.flip();
                Charset charset = StandardCharsets.UTF_8;
                System.out.println(charset.decode(res).toString());

            }
        }
    }

}
