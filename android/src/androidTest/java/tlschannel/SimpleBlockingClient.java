package tlschannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CountDownLatch;

import javax.net.ssl.SSLContext;

public class SimpleBlockingClient {
  private static final Charset utf8 = StandardCharsets.UTF_8;

  public static String exchange(String host, int port, String msg) throws GeneralSecurityException, IOException, InterruptedException {
    SSLContext sslContext = ContextFactory.authenticatedContext("TLSv1.2");
    try (SocketChannel rawChannel = SocketChannel.open()) {
      rawChannel.connect(new InetSocketAddress(host, port));
      ClientTlsChannel.Builder builder = ClientTlsChannel.newBuilder(rawChannel, sslContext);
      try (TlsChannel tlsChannel = builder.build()) {
        CountDownLatch cdl = new CountDownLatch(1);
        new Thread(() -> {
          try {
            tlsChannel.write(ByteBuffer.wrap(msg.getBytes(utf8)));
          } catch (Throwable t) {
            t.printStackTrace();
          }
          cdl.countDown();
        }).start();
        ByteBuffer res = ByteBuffer.allocate(10000);
        tlsChannel.read(res);
        res.flip();
        cdl.await();
        return utf8.decode(res).toString();
      }
    }
  }
}
