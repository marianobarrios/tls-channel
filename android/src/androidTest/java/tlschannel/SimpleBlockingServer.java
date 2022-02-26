package tlschannel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.concurrent.CountDownLatch;

import javax.net.ssl.SSLContext;

public class SimpleBlockingServer {
  private static final Charset utf8 = StandardCharsets.UTF_8;

  public static String exchange(int port, String msg) throws IOException, GeneralSecurityException, InterruptedException {
    SSLContext sslContext = ContextFactory.authenticatedContext("TLSv1.2");
    try (ServerSocketChannel serverSocket = ServerSocketChannel.open()) {
      serverSocket.socket().bind(new InetSocketAddress(port));
      System.out.println("Waiting for connection...");
      try (SocketChannel rawChannel = serverSocket.accept()) {
        ServerTlsChannel.Builder builder = ServerTlsChannel.newBuilder(rawChannel, sslContext);
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
}
