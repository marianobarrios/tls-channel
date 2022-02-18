package tlschannel.example;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.CompletionHandler;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLContext;
import tlschannel.ServerTlsChannel;
import tlschannel.TlsChannel;
import tlschannel.async.AsynchronousTlsChannel;
import tlschannel.async.AsynchronousTlsChannelGroup;

/**
 * Server asynchronous example. Accepts any number of connections and echos bytes sent by the
 * clients into standard output.
 *
 * <p>To test, use: <code> openssl s_client -connect localhost:10000 </code>
 *
 * <p>This class exemplifies the use of {@link AsynchronousTlsChannel}. It implements a blocking
 * select loop, that processes new connections asynchronously using asynchronous channel and
 * callbacks, hiding the complexity of a select loop.
 */
public class AsynchronousChannelServer {

  private static final Charset utf8 = StandardCharsets.UTF_8;

  public static void main(String[] args) throws IOException, GeneralSecurityException {

    // initialize the SSLContext, a configuration holder, reusable object
    SSLContext sslContext = ContextFactory.authenticatedContext("TLSv1.2");

    AsynchronousTlsChannelGroup channelGroup = new AsynchronousTlsChannelGroup();

    // connect server socket channel and register it in the selector
    try (ServerSocketChannel serverSocket = ServerSocketChannel.open()) {

      serverSocket.socket().bind(new InetSocketAddress(10000));

      // accept raw connections normally
      System.out.println("Waiting for connection...");

      while (true) {
        SocketChannel rawChannel = serverSocket.accept();
        rawChannel.configureBlocking(false);

        // create TlsChannel builder, combining the raw channel and the SSLEngine, using minimal
        // options
        ServerTlsChannel.Builder builder = ServerTlsChannel.newBuilder(rawChannel, sslContext);

        // instantiate TlsChannel
        TlsChannel tlsChannel = builder.build();

        // build asynchronous channel, based in the TLS channel and associated with the global
        // group.
        AsynchronousTlsChannel asyncTlsChannel =
            new AsynchronousTlsChannel(channelGroup, tlsChannel, rawChannel);

        // write to stdout all data sent by the client
        ByteBuffer res = ByteBuffer.allocate(10000);
        asyncTlsChannel.read(
            res,
            null,
            new CompletionHandler<Integer, Object>() {
              @Override
              public void completed(Integer result, Object attachment) {
                if (result != -1) {
                  res.flip();
                  System.out.print(utf8.decode(res).toString());
                  res.compact();
                  // repeat
                  asyncTlsChannel.read(res, null, this);
                } else {
                  try {
                    asyncTlsChannel.close();
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                }
              }

              @Override
              public void failed(Throwable exc, Object attachment) {
                try {
                  asyncTlsChannel.close();
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
                throw new RuntimeException(exc);
              }
            });
      }
    }
  }
}
