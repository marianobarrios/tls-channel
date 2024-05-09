package tlschannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Optional;
import javax.net.ssl.SSLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import tlschannel.helpers.SocketPairFactory;
import tlschannel.helpers.SslContextFactory;
import tlschannel.helpers.TestJavaUtil;

@TestInstance(Lifecycle.PER_CLASS)
public class FailTest {

    private final SslContextFactory sslContextFactory = new SslContextFactory();
    private final SocketPairFactory factory = new SocketPairFactory(sslContextFactory.defaultContext());

    @Test
    public void testPlanToTls() throws IOException, InterruptedException {
        ServerSocketChannel serverSocket = ServerSocketChannel.open();
        serverSocket.bind(new InetSocketAddress(factory.localhost, 0 /* find free port */));
        int chosenPort = ((InetSocketAddress) serverSocket.getLocalAddress()).getPort();
        InetSocketAddress address = new InetSocketAddress(factory.localhost, chosenPort);
        SocketChannel clientChannel = SocketChannel.open(address);
        SocketChannel rawServer = serverSocket.accept();
        factory.createClientSslEngine(Optional.empty(), chosenPort);
        ServerTlsChannel.Builder serverChannelBuilder = ServerTlsChannel.newBuilder(
                        rawServer,
                        nameOpt -> factory.sslContextFactory(factory.clientSniHostName, factory.sslContext, nameOpt))
                .withEngineFactory(
                        sslContext -> factory.fixedCipherServerSslEngineFactory(Optional.empty(), sslContext));

        ServerTlsChannel serverChannel = serverChannelBuilder.build();

        Runnable serverFn = () -> {
            TestJavaUtil.cannotFail(() -> {
                ByteBuffer buffer = ByteBuffer.allocate(10000);
                assertThrows(SSLException.class, () -> serverChannel.read(buffer));
                try {
                    serverChannel.close();
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        };
        Thread serverThread = new Thread(serverFn, "server-thread");
        serverThread.start();

        String message = "12345\n";
        clientChannel.write(ByteBuffer.wrap(message.getBytes()));
        ByteBuffer buffer = ByteBuffer.allocate(1);
        assertEquals(-1, clientChannel.read(buffer));
        clientChannel.close();

        serverThread.join();
    }
}
