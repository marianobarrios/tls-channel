package tlschannel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedChannelException;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import tlschannel.helpers.*;
import tlschannel.helpers.SocketPairFactory.ChuckSizes;
import tlschannel.helpers.SocketPairFactory.ChunkSizeConfig;

@TestInstance(Lifecycle.PER_CLASS)
public class CloseTest {

    private final SslContextFactory sslContextFactory = new SslContextFactory();

    private final SocketPairFactory factory = new SocketPairFactory(sslContextFactory.defaultContext());
    private final byte[] data = new byte[] {15};

    /**
     * Less than a TLS message, to force read/write loops
     */
    private final Optional<Integer> internalBufferSize = Optional.of(10);

    @Test
    void testTcpImmediateClose() throws InterruptedException, IOException {
        SocketGroups.SocketPair socketPair = factory.nioNio(
                Optional.empty(),
                Optional.of(new ChunkSizeConfig(
                        new ChuckSizes(internalBufferSize, Optional.empty()),
                        new ChuckSizes(internalBufferSize, Optional.empty()))),
                true,
                false,
                Optional.empty());
        SocketGroups.SocketGroup clientGroup = socketPair.client;
        SocketGroups.SocketGroup serverGroup = socketPair.server;
        ByteChannel client = clientGroup.external;
        ByteChannel server = serverGroup.external;
        Runnable clientFn = TestJavaUtil.cannotFailRunnable(() -> {
            clientGroup.plain.close();
            assertFalse(clientGroup.tls.shutdownSent());
            assertFalse(clientGroup.tls.shutdownReceived());
            Assertions.assertThrows(ClosedChannelException.class, () -> client.write(ByteBuffer.wrap(data)));
        });
        Runnable serverFn = TestJavaUtil.cannotFailRunnable(() -> {
            ByteBuffer buffer = ByteBuffer.allocate(1);
            assertEquals(-1, server.read(buffer));
            assertFalse(serverGroup.tls.shutdownReceived());
            assertFalse(serverGroup.tls.shutdownSent());
            // repeated
            assertEquals(-1, server.read(buffer));
        });
        Thread clientThread = new Thread(clientFn, "client-thread");
        Thread serverThread = new Thread(serverFn, "server-thread");
        clientThread.start();
        serverThread.start();
        clientThread.join();
        serverThread.join();
        clientGroup.tls.close();
        serverGroup.tls.close();
        SocketPairFactory.checkDeallocation(socketPair);
    }

    @Test
    void testTcpClose() throws InterruptedException, IOException {
        SocketGroups.SocketPair socketPair = factory.nioNio(
                Optional.empty(),
                Optional.of(new ChunkSizeConfig(
                        new ChuckSizes(internalBufferSize, Optional.empty()),
                        new ChuckSizes(internalBufferSize, Optional.empty()))),
                true,
                false,
                Optional.empty());
        SocketGroups.SocketGroup clientGroup = socketPair.client;
        SocketGroups.SocketGroup serverGroup = socketPair.server;
        ByteChannel client = clientGroup.external;
        ByteChannel server = serverGroup.external;
        Runnable clientFn = TestJavaUtil.cannotFailRunnable(() -> {
            client.write(ByteBuffer.wrap(data));
            clientGroup.plain.close();
            assertFalse(clientGroup.tls.shutdownSent());
            assertFalse(clientGroup.tls.shutdownReceived());
            Assertions.assertThrows(ClosedChannelException.class, () -> client.write(ByteBuffer.wrap(data)));
        });
        Runnable serverFn = TestJavaUtil.cannotFailRunnable(() -> {
            ByteBuffer buffer = ByteBuffer.allocate(1);
            assertEquals(1, server.read(buffer));
            buffer.flip();
            assertEquals(ByteBuffer.wrap(data), buffer);
            buffer.clear();
            assertEquals(-1, server.read(buffer));
            assertFalse(serverGroup.tls.shutdownReceived());
            assertFalse(serverGroup.tls.shutdownSent());
            // repeated
            assertEquals(-1, server.read(buffer));
        });
        Thread clientThread = new Thread(clientFn, "client-thread");
        Thread serverThread = new Thread(serverFn, "server-thread");
        clientThread.start();
        serverThread.start();
        clientThread.join();
        serverThread.join();
        clientGroup.tls.close();
        serverGroup.tls.close();
        SocketPairFactory.checkDeallocation(socketPair);
    }

    @Test
    void testClose() throws InterruptedException {
        SocketGroups.SocketPair socketPair = factory.nioNio(
                Optional.empty(),
                Optional.of(new ChunkSizeConfig(
                        new ChuckSizes(internalBufferSize, Optional.empty()),
                        new ChuckSizes(internalBufferSize, Optional.empty()))),
                true,
                false,
                Optional.empty());
        SocketGroups.SocketGroup clientGroup = socketPair.client;
        SocketGroups.SocketGroup serverGroup = socketPair.server;
        ByteChannel client = clientGroup.external;
        ByteChannel server = serverGroup.external;
        Runnable clientFn = TestJavaUtil.cannotFailRunnable(() -> {
            client.write(ByteBuffer.wrap(data));
            client.close();
            assertTrue(clientGroup.tls.shutdownSent());
            assertFalse(clientGroup.tls.shutdownReceived());
            Assertions.assertThrows(ClosedChannelException.class, () -> client.write(ByteBuffer.wrap(data)));
        });
        Runnable serverFn = TestJavaUtil.cannotFailRunnable(() -> {
            ByteBuffer buffer = ByteBuffer.allocate(1);
            assertEquals(1, server.read(buffer));
            buffer.flip();
            assertEquals(ByteBuffer.wrap(data), buffer);
            buffer.clear();
            assertEquals(-1, server.read(buffer));
            assertTrue(serverGroup.tls.shutdownReceived());
            assertFalse(serverGroup.tls.shutdownSent());
            // repeated
            assertEquals(-1, server.read(buffer));
            server.close();
            Assertions.assertThrows(ClosedChannelException.class, () -> server.read(buffer));
        });
        Thread clientThread = new Thread(clientFn, "client-thread");
        Thread serverThread = new Thread(serverFn, "server-thread");
        clientThread.start();
        serverThread.start();
        clientThread.join();
        serverThread.join();
        SocketPairFactory.checkDeallocation(socketPair);
    }

    @Test
    void testCloseAndWait() throws InterruptedException {
        SocketGroups.SocketPair socketPair = factory.nioNio(
                Optional.empty(),
                Optional.of(new ChunkSizeConfig(
                        new ChuckSizes(internalBufferSize, Optional.empty()),
                        new ChuckSizes(internalBufferSize, Optional.empty()))),
                true,
                true,
                Optional.empty());
        SocketGroups.SocketGroup clientGroup = socketPair.client;
        SocketGroups.SocketGroup serverGroup = socketPair.server;
        ByteChannel client = clientGroup.external;
        ByteChannel server = serverGroup.external;
        Runnable clientFn = TestJavaUtil.cannotFailRunnable(() -> {
            client.write(ByteBuffer.wrap(data));
            client.close();
            assertTrue(clientGroup.tls.shutdownReceived());
            assertTrue(clientGroup.tls.shutdownSent());
            Assertions.assertThrows(ClosedChannelException.class, () -> client.write(ByteBuffer.wrap(data)));
        });
        Runnable serverFn = TestJavaUtil.cannotFailRunnable(() -> {
            ByteBuffer buffer = ByteBuffer.allocate(1);
            assertEquals(1, server.read(buffer));
            buffer.flip();
            assertEquals(ByteBuffer.wrap(data), buffer);
            buffer.clear();
            assertEquals(-1, server.read(buffer));
            // repeated
            assertEquals(-1, server.read(buffer));
            server.close();
            assertTrue(serverGroup.tls.shutdownReceived());
            assertTrue(serverGroup.tls.shutdownSent());
            Assertions.assertThrows(ClosedChannelException.class, () -> server.read(buffer));
        });
        Thread clientThread = new Thread(clientFn, "client-thread");
        Thread serverThread = new Thread(serverFn, "server-thread");
        clientThread.start();
        serverThread.start();
        clientThread.join();
        serverThread.join();
        SocketPairFactory.checkDeallocation(socketPair);
    }

    @Test
    void testCloseAndWaitForever() throws IOException, InterruptedException {
        SocketGroups.SocketPair socketPair = factory.nioNio(
                Optional.empty(),
                Optional.of(new ChunkSizeConfig(
                        new ChuckSizes(internalBufferSize, Optional.empty()),
                        new ChuckSizes(internalBufferSize, Optional.empty()))),
                true,
                true,
                Optional.empty());
        SocketGroups.SocketGroup clientGroup = socketPair.client;
        SocketGroups.SocketGroup serverGroup = socketPair.server;
        ByteChannel client = clientGroup.external;
        ByteChannel server = serverGroup.external;
        Runnable clientFn = TestJavaUtil.cannotFailRunnable(() -> {
            client.write(ByteBuffer.wrap(data));
            client.close();
        });
        Runnable serverFn = TestJavaUtil.cannotFailRunnable(() -> {
            ByteBuffer buffer = ByteBuffer.allocate(1);
            assertEquals(1, server.read(buffer));
            buffer.flip();
            assertEquals(ByteBuffer.wrap(data), buffer);
            buffer.clear();
            assertEquals(-1, server.read(buffer));
            assertTrue(serverGroup.tls.shutdownReceived());
            assertFalse(serverGroup.tls.shutdownSent());
            // repeated
            assertEquals(-1, server.read(buffer));
        });
        Thread clientThread = new Thread(clientFn, "client-thread");
        Thread serverThread = new Thread(serverFn, "server-thread");
        clientThread.start();
        serverThread.start();
        clientThread.join(5000);
        serverThread.join();
        assertTrue(clientThread.isAlive());
        serverGroup.tls.close();
        clientThread.join();
        SocketPairFactory.checkDeallocation(socketPair);
    }

    @Test
    void testShutdownAndForget() throws InterruptedException, IOException {
        SocketGroups.SocketPair socketPair = factory.nioNio(
                Optional.empty(),
                Optional.of(new ChunkSizeConfig(
                        new ChuckSizes(internalBufferSize, Optional.empty()),
                        new ChuckSizes(internalBufferSize, Optional.empty()))),
                true,
                false,
                Optional.empty());
        SocketGroups.SocketGroup clientGroup = socketPair.client;
        SocketGroups.SocketGroup serverGroup = socketPair.server;
        ByteChannel client = clientGroup.external;
        ByteChannel server = serverGroup.external;
        Runnable clientFn = TestJavaUtil.cannotFailRunnable(() -> {
            client.write(ByteBuffer.wrap(data));
            assertFalse(clientGroup.tls.shutdown());
            assertFalse(clientGroup.tls.shutdownReceived());
            assertTrue(clientGroup.tls.shutdownSent());
            Assertions.assertThrows(ClosedChannelException.class, () -> client.write(ByteBuffer.wrap(data)));
        });
        Runnable serverFn = TestJavaUtil.cannotFailRunnable(() -> {
            ByteBuffer buffer = ByteBuffer.allocate(1);
            assertEquals(1, server.read(buffer));
            buffer.flip();
            assertEquals(ByteBuffer.wrap(data), buffer);
            buffer.clear();
            assertEquals(-1, server.read(buffer));
            assertTrue(serverGroup.tls.shutdownReceived());
            assertFalse(serverGroup.tls.shutdownSent());
        });
        Thread clientThread = new Thread(clientFn, "client-thread");
        Thread serverThread = new Thread(serverFn, "server-thread");
        clientThread.start();
        serverThread.start();
        clientThread.join();
        serverThread.join();
        client.close();
        server.close();
        SocketPairFactory.checkDeallocation(socketPair);
    }

    @Test
    void testShutdownAndWait() throws IOException, InterruptedException {
        SocketGroups.SocketPair socketPair = factory.nioNio(
                Optional.empty(),
                Optional.of(new ChunkSizeConfig(
                        new ChuckSizes(internalBufferSize, Optional.empty()),
                        new ChuckSizes(internalBufferSize, Optional.empty()))),
                true,
                false,
                Optional.empty());
        SocketGroups.SocketGroup clientGroup = socketPair.client;
        SocketGroups.SocketGroup serverGroup = socketPair.server;
        ByteChannel client = clientGroup.external;
        ByteChannel server = serverGroup.external;
        Runnable clientFn = TestJavaUtil.cannotFailRunnable(() -> {
            client.write(ByteBuffer.wrap(data));
            // send first close_notify
            assertFalse(clientGroup.tls.shutdown());
            assertFalse(clientGroup.tls.shutdownReceived());
            assertTrue(clientGroup.tls.shutdownSent());
            Assertions.assertThrows(ClosedChannelException.class, () -> client.write(ByteBuffer.wrap(data)));
            // wait for second close_notify
            assertTrue(clientGroup.tls.shutdown());
            assertTrue(clientGroup.tls.shutdownReceived());
            assertTrue(clientGroup.tls.shutdownSent());
        });
        Runnable serverFn = TestJavaUtil.cannotFailRunnable(() -> {
            ByteBuffer buffer = ByteBuffer.allocate(1);
            assertEquals(1, server.read(buffer));
            buffer.flip();
            assertEquals(ByteBuffer.wrap(data), buffer);
            buffer.clear();
            assertEquals(-1, server.read(buffer));
            assertTrue(serverGroup.tls.shutdownReceived());
            assertFalse(serverGroup.tls.shutdownSent());
            assertEquals(-1, server.read(buffer));
            // send second close_notify
            assertTrue(serverGroup.tls.shutdown());
            assertTrue(serverGroup.tls.shutdownReceived());
            assertTrue(serverGroup.tls.shutdownSent());
        });
        Thread clientThread = new Thread(clientFn, "client-thread");
        Thread serverThread = new Thread(serverFn, "server-thread");
        clientThread.start();
        serverThread.start();
        clientThread.join();
        serverThread.join();
        client.close();
        server.close();
        SocketPairFactory.checkDeallocation(socketPair);
    }

    @Test
    void testShutdownAndWaitForever() throws InterruptedException, IOException {
        SocketGroups.SocketPair socketPair = factory.nioNio(
                Optional.empty(),
                Optional.of(new ChunkSizeConfig(
                        new ChuckSizes(internalBufferSize, Optional.empty()),
                        new ChuckSizes(internalBufferSize, Optional.empty()))),
                true,
                false,
                Optional.empty());
        SocketGroups.SocketGroup clientGroup = socketPair.client;
        SocketGroups.SocketGroup serverGroup = socketPair.server;
        ByteChannel client = clientGroup.external;
        ByteChannel server = serverGroup.external;
        Runnable clientFn = TestJavaUtil.cannotFailRunnable(() -> {
            client.write(ByteBuffer.wrap(data));
            // send first close_notify
            assertFalse(clientGroup.tls.shutdown());
            assertFalse(clientGroup.tls.shutdownReceived());
            assertTrue(clientGroup.tls.shutdownSent());
            Assertions.assertThrows(ClosedChannelException.class, () -> client.write(ByteBuffer.wrap(data)));
            // wait for second close_notify
            Assertions.assertThrows(AsynchronousCloseException.class, () -> clientGroup.tls.shutdown());
        });
        Runnable serverFn = TestJavaUtil.cannotFailRunnable(() -> {
            ByteBuffer buffer = ByteBuffer.allocate(1);
            assertEquals(1, server.read(buffer));
            buffer.flip();
            assertEquals(ByteBuffer.wrap(data), buffer);
            buffer.clear();
            assertEquals(-1, server.read(buffer));
            assertTrue(serverGroup.tls.shutdownReceived());
            assertFalse(serverGroup.tls.shutdownSent());
            assertEquals(-1, server.read(buffer));
            // do not send second close_notify
        });
        Thread clientThread = new Thread(clientFn, "client-thread");
        Thread serverThread = new Thread(serverFn, "server-thread");
        clientThread.start();
        serverThread.start();
        serverThread.join();
        clientThread.join(5000);
        assertTrue(clientThread.isAlive());
        client.close();
        server.close();
        SocketPairFactory.checkDeallocation(socketPair);
    }
}
