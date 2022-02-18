package tlschannel.example;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Iterator;
import javax.net.ssl.SSLContext;
import tlschannel.ClientTlsChannel;
import tlschannel.NeedsReadException;
import tlschannel.NeedsWriteException;
import tlschannel.TlsChannel;

/** Client non-blocking example. Connects to a public TLS reporting service. */
public class NonBlockingClient {

  private static final Charset utf8 = StandardCharsets.UTF_8;

  public static void main(String[] args) throws IOException, GeneralSecurityException {

    ByteBuffer requestBuffer =
        ByteBuffer.wrap(SimpleBlockingClient.httpLine.getBytes(StandardCharsets.US_ASCII));
    ByteBuffer responseBuffer =
        ByteBuffer.allocate(1000); // use small buffer to cause selector loops
    boolean requestSent = false;

    // initialize the SSLContext, a configuration holder, reusable object
    SSLContext sslContext = SSLContext.getDefault();

    Selector selector = Selector.open();

    // connect raw socket channel normally
    try (SocketChannel rawChannel = SocketChannel.open()) {
      rawChannel.configureBlocking(false);
      rawChannel.connect(new InetSocketAddress(SimpleBlockingClient.domain, 443));

      // Note that the raw channel (and not the wrapped one) is registered in the selector
      rawChannel.register(selector, SelectionKey.OP_CONNECT);

      // create TlsChannel builder, combining the raw channel and the SSLEngine, using minimal
      // options
      ClientTlsChannel.Builder builder = ClientTlsChannel.newBuilder(rawChannel, sslContext);

      // instantiate TlsChannel
      try (TlsChannel tlsChannel = builder.build()) {

        mainloop:
        while (true) {

          // loop blocks here
          selector.select();

          Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
          while (iterator.hasNext()) {

            SelectionKey key = iterator.next();
            iterator.remove();

            if (key.isConnectable()) {

              if (rawChannel.finishConnect()) {
                // the channel is registered for writing, because TLS connections are initiated by
                // clients.
                rawChannel.register(selector, SelectionKey.OP_WRITE);
              }

            } else if (key.isReadable() || key.isWritable()) {

              try {
                if (!requestSent) {
                  // do HTTP request
                  tlsChannel.write(requestBuffer);
                  if (requestBuffer.remaining() == 0) {
                    requestSent = true;
                  }

                } else {
                  // handle HTTP response
                  int c = tlsChannel.read(responseBuffer);
                  if (c > 0) {
                    responseBuffer.flip();
                    System.out.print(utf8.decode(responseBuffer));
                    responseBuffer.compact();
                  }
                  if (c < 0) {
                    tlsChannel.close();
                    break mainloop;
                  }
                }
              } catch (NeedsReadException e) {
                key.interestOps(SelectionKey.OP_READ); // overwrites previous value
              } catch (NeedsWriteException e) {
                key.interestOps(SelectionKey.OP_WRITE); // overwrites previous value
              }

            } else {
              throw new IllegalStateException();
            }
          }
        }
      }
    }
  }
}
