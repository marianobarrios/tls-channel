package tlschannel.helpers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.ByteChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.net.ssl.*;
import tlschannel.*;
import tlschannel.async.AsynchronousTlsChannel;
import tlschannel.async.AsynchronousTlsChannelGroup;
import tlschannel.helpers.SocketGroups.*;

/** Create pairs of connected sockets (using the loopback interface). Additionally, all the raw (non-encrypted) socket
 * channel are wrapped with a chunking decorator that partitions the bytesProduced of any read or write operation.
 */
public class SocketPairFactory {

    private static final Logger logger = Logger.getLogger(SocketPairFactory.class.getName());

    public static final String NULL_CIPHER = "null-cipher";

    private static final int maxAllowedKeyLength;

    static {
        try {
            maxAllowedKeyLength = Cipher.getMaxAllowedKeyLength("AES");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static class ChunkSizeConfig {
        public final ChuckSizes clientChuckSize;
        public final ChuckSizes serverChunkSize;

        public ChunkSizeConfig(ChuckSizes clientChuckSize, ChuckSizes serverChunkSize) {
            this.clientChuckSize = clientChuckSize;
            this.serverChunkSize = serverChunkSize;
        }
    }

    public static class ChuckSizes {
        public final Optional<Integer> internalSize;
        public final Optional<Integer> externalSize;

        public ChuckSizes(Optional<Integer> internalSize, Optional<Integer> externalSize) {
            this.internalSize = internalSize;
            this.externalSize = externalSize;
        }
    }

    public final SSLContext sslContext;
    private final String serverName;
    private final boolean releaseBuffers = true;
    public final SNIHostName clientSniHostName;
    private final SNIMatcher expectedSniHostName;
    public final InetAddress localhost;

    private final SSLSocketFactory sslSocketFactory;
    private final SSLServerSocketFactory sslServerSocketFactory;

    private final TrackingAllocator globalPlainTrackingAllocator = new TrackingAllocator(new HeapBufferAllocator());
    private final TrackingAllocator globalEncryptedTrackingAllocator = new TrackingAllocator(new HeapBufferAllocator());

    public SocketPairFactory(SSLContext sslContext, String serverName) {
        this.sslContext = sslContext;
        this.serverName = serverName;
        this.clientSniHostName = new SNIHostName(serverName);
        this.expectedSniHostName = SNIHostName.createSNIMatcher(serverName /* regex! */);
        try {
            this.localhost = InetAddress.getByName(null);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);
        }
        this.sslSocketFactory = sslContext.getSocketFactory();
        this.sslServerSocketFactory = sslContext.getServerSocketFactory();
        logger.info(() -> String.format("AES max key length: %s", maxAllowedKeyLength));
    }

    public SocketPairFactory(SSLContext sslContext) {
        this(sslContext, SslContextFactory.certificateCommonName);
    }

    public SSLEngine fixedCipherServerSslEngineFactory(Optional<String> cipher, SSLContext sslContext) {
        SSLEngine engine = sslContext.createSSLEngine();
        engine.setUseClientMode(false);
        cipher.ifPresent(c -> engine.setEnabledCipherSuites(new String[] {c}));
        return engine;
    }

    public Optional<SSLContext> sslContextFactory(
            SNIServerName expectedName, SSLContext sslContext, Optional<SNIServerName> name) {
        if (name.isPresent()) {
            SNIServerName n = name.get();
            logger.warning(() -> "ContextFactory, requested name: " + n);
            if (!expectedSniHostName.matches(n)) {
                throw new IllegalArgumentException(String.format("Received SNI $n does not match %s", serverName));
            }
            return Optional.of(sslContext);
        } else {
            throw new IllegalArgumentException("SNI expected");
        }
    }

    public SSLEngine createClientSslEngine(Optional<String> cipher, int peerPort) {
        SSLEngine engine = sslContext.createSSLEngine(serverName, peerPort);
        engine.setUseClientMode(true);
        cipher.ifPresent(c -> engine.setEnabledCipherSuites(new String[] {c}));
        SSLParameters sslParams = engine.getSSLParameters(); // returns a value object
        sslParams.setEndpointIdentificationAlgorithm("HTTPS");
        sslParams.setServerNames(Collections.singletonList(clientSniHostName));
        engine.setSSLParameters(sslParams);
        return engine;
    }

    private SSLServerSocket createSslServerSocket(Optional<String> cipher) {
        try {
            SSLServerSocket serverSocket =
                    (SSLServerSocket) sslServerSocketFactory.createServerSocket(0 /* find free port */);
            cipher.ifPresent(c -> serverSocket.setEnabledCipherSuites(new String[] {c}));
            return serverSocket;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private SSLSocket createSslSocket(Optional<String> cipher, InetAddress host, int port, String requestedHost) {
        try {
            SSLSocket socket = (SSLSocket) sslSocketFactory.createSocket(host, port);
            cipher.ifPresent(c -> socket.setEnabledCipherSuites(new String[] {c}));
            return socket;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public OldOldSocketPair oldOld(Optional<String> cipher) {
        try {
            SSLServerSocket serverSocket = createSslServerSocket(cipher);
            int chosenPort = serverSocket.getLocalPort();
            SSLSocket client = createSslSocket(cipher, localhost, chosenPort, serverName);
            SSLParameters sslParameters = client.getSSLParameters(); // returns a value object
            sslParameters.setServerNames(Collections.singletonList(clientSniHostName));
            client.setSSLParameters(sslParameters);
            SSLSocket server = (SSLSocket) serverSocket.accept();
            serverSocket.close();
            return new OldOldSocketPair(client, server);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public OldNioSocketPair oldNio(Optional<String> cipher) {
        try {
            ServerSocketChannel serverSocket = ServerSocketChannel.open();
            serverSocket.bind(new InetSocketAddress(localhost, 0 /* find free port */));
            int chosenPort = ((InetSocketAddress) serverSocket.getLocalAddress()).getPort();
            SSLSocket client = createSslSocket(cipher, localhost, chosenPort, serverName);
            SSLParameters sslParameters = client.getSSLParameters(); // returns a value object
            sslParameters.setServerNames(Collections.singletonList(clientSniHostName));
            client.setSSLParameters(sslParameters);
            SocketChannel rawServer = serverSocket.accept();
            serverSocket.close();
            ServerTlsChannel server = ServerTlsChannel.newBuilder(
                            rawServer, nameOpt -> sslContextFactory(clientSniHostName, sslContext, nameOpt))
                    .withEngineFactory(x -> fixedCipherServerSslEngineFactory(cipher, x))
                    .build();
            return new OldNioSocketPair(client, new SocketGroup(server, server, rawServer));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public NioOldSocketPair nioOld(Optional<String> cipher) {
        try {
            SSLServerSocket serverSocket = createSslServerSocket(cipher);
            int chosenPort = serverSocket.getLocalPort();
            InetSocketAddress address = new InetSocketAddress(localhost, chosenPort);
            SocketChannel rawClient = SocketChannel.open(address);
            SSLSocket server = (SSLSocket) serverSocket.accept();
            serverSocket.close();
            ClientTlsChannel client = ClientTlsChannel.newBuilder(rawClient, createClientSslEngine(cipher, chosenPort))
                    .build();
            return new NioOldSocketPair(new SocketGroup(client, client, rawClient), server);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public SocketPair nioNio(
            Optional<String> cipher,
            Optional<ChunkSizeConfig> chunkSizeConfig,
            boolean runTasks,
            boolean waitForCloseConfirmation,
            Optional<AsynchronousTlsChannelGroup> pseudoAsyncGroup) {
        return nioNioN(cipher, 1, chunkSizeConfig, runTasks, waitForCloseConfirmation, pseudoAsyncGroup)
                .get(0);
    }

    public List<SocketPair> nioNioN(
            Optional<String> cipher,
            int qtty,
            Optional<ChunkSizeConfig> chunkSizeConfig,
            boolean runTasks,
            boolean waitForCloseConfirmation,
            Optional<AsynchronousTlsChannelGroup> pseudoAsyncGroup) {
        try (ServerSocketChannel serverSocket = ServerSocketChannel.open()) {
            serverSocket.bind(new InetSocketAddress(localhost, 0 /* find free port */));
            int chosenPort = ((InetSocketAddress) serverSocket.getLocalAddress()).getPort();
            InetSocketAddress address = new InetSocketAddress(localhost, chosenPort);
            List<SocketPair> pairs = new ArrayList<>();
            for (int i = 0; i < qtty; i++) {
                SocketChannel rawClient = SocketChannel.open(address);
                SocketChannel rawServer = serverSocket.accept();

                ByteChannel plainClient;
                if (chunkSizeConfig.isPresent()) {
                    Optional<Integer> internalSize = chunkSizeConfig.get().clientChuckSize.internalSize;
                    if (internalSize.isPresent()) {
                        plainClient = new ChunkingByteChannel(rawClient, internalSize.get());
                    } else {
                        plainClient = rawClient;
                    }
                } else {
                    plainClient = rawClient;
                }

                ByteChannel plainServer;
                if (chunkSizeConfig.isPresent()) {
                    Optional<Integer> internalSize = chunkSizeConfig.get().serverChunkSize.internalSize;
                    if (internalSize.isPresent()) {
                        plainServer = new ChunkingByteChannel(rawServer, internalSize.get());
                    } else {
                        plainServer = rawServer;
                    }
                } else {
                    plainServer = rawServer;
                }

                SSLEngine clientEngine;
                if (cipher.equals(Optional.of(NULL_CIPHER))) {
                    clientEngine = new NullSslEngine();
                } else {
                    clientEngine = createClientSslEngine(cipher, chosenPort);
                }

                ClientTlsChannel clientChannel = ClientTlsChannel.newBuilder(plainClient, clientEngine)
                        .withRunTasks(runTasks)
                        .withWaitForCloseConfirmation(waitForCloseConfirmation)
                        .withPlainBufferAllocator(globalPlainTrackingAllocator)
                        .withEncryptedBufferAllocator(globalEncryptedTrackingAllocator)
                        .withReleaseBuffers(releaseBuffers)
                        .build();

                ServerTlsChannel.Builder serverChannelBuilder;
                if (cipher.equals(Optional.of(NULL_CIPHER))) {
                    serverChannelBuilder = ServerTlsChannel.newBuilder(plainServer, new NullSslContext());
                } else {
                    serverChannelBuilder = ServerTlsChannel.newBuilder(
                                    plainServer, nameOpt -> sslContextFactory(clientSniHostName, sslContext, nameOpt))
                            .withEngineFactory(ctx -> fixedCipherServerSslEngineFactory(cipher, ctx));
                }

                ServerTlsChannel serverChannel = serverChannelBuilder
                        .withRunTasks(runTasks)
                        .withWaitForCloseConfirmation(waitForCloseConfirmation)
                        .withPlainBufferAllocator(globalPlainTrackingAllocator)
                        .withEncryptedBufferAllocator(globalEncryptedTrackingAllocator)
                        .withReleaseBuffers(releaseBuffers)
                        .build();

                /*
                 * Handler executor can be null because BlockerByteChannel will only use Futures, never callbacks.
                 */

                ByteChannel clientAsyncChannel;
                if (pseudoAsyncGroup.isPresent()) {
                    rawClient.configureBlocking(false);
                    clientAsyncChannel = new BlockerByteChannel(
                            new AsynchronousTlsChannel(pseudoAsyncGroup.get(), clientChannel, rawClient));
                } else {
                    clientAsyncChannel = clientChannel;
                }

                ByteChannel serverAsyncChannel;
                if (pseudoAsyncGroup.isPresent()) {
                    rawServer.configureBlocking(false);
                    serverAsyncChannel = new BlockerByteChannel(
                            new AsynchronousTlsChannel(pseudoAsyncGroup.get(), serverChannel, rawServer));
                } else {
                    serverAsyncChannel = serverChannel;
                }

                ByteChannel externalClient;
                if (chunkSizeConfig.isPresent()) {
                    Optional<Integer> size = chunkSizeConfig.get().clientChuckSize.externalSize;
                    if (size.isPresent()) {
                        externalClient = new ChunkingByteChannel(clientAsyncChannel, size.get());
                    } else {
                        externalClient = clientChannel;
                    }
                } else {
                    externalClient = clientChannel;
                }

                ByteChannel externalServer;
                if (chunkSizeConfig.isPresent()) {
                    Optional<Integer> size = chunkSizeConfig.get().serverChunkSize.externalSize;
                    if (size.isPresent()) {
                        externalServer = new ChunkingByteChannel(serverAsyncChannel, size.get());
                    } else {
                        externalServer = serverChannel;
                    }
                } else {
                    externalServer = serverChannel;
                }

                SocketGroup clientPair = new SocketGroup(externalClient, clientChannel, rawClient);
                SocketGroup serverPair = new SocketGroup(externalServer, serverChannel, rawServer);
                pairs.add(new SocketPair(clientPair, serverPair));
            }
            return pairs;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public AsyncSocketPair async(
            Optional<String> cipher,
            AsynchronousTlsChannelGroup channelGroup,
            boolean runTasks,
            boolean waitForCloseConfirmation) {
        return asyncN(cipher, channelGroup, 1, runTasks, waitForCloseConfirmation)
                .get(0);
    }

    public List<AsyncSocketPair> asyncN(
            Optional<String> cipher,
            AsynchronousTlsChannelGroup channelGroup,
            int qtty,
            boolean runTasks,
            boolean waitForCloseConfirmation) {
        try (ServerSocketChannel serverSocket = ServerSocketChannel.open()) {
            serverSocket.bind(new InetSocketAddress(localhost, 0 /* find free port */));
            int chosenPort = ((InetSocketAddress) serverSocket.getLocalAddress()).getPort();
            InetSocketAddress address = new InetSocketAddress(localhost, chosenPort);

            List<AsyncSocketPair> pairs = new ArrayList<>();
            for (int i = 0; i < qtty; i++) {
                SocketChannel rawClient = SocketChannel.open(address);
                SocketChannel rawServer = serverSocket.accept();

                rawClient.configureBlocking(false);
                rawServer.configureBlocking(false);

                SSLEngine clientEngine;
                if (cipher.equals(Optional.of(NULL_CIPHER))) {
                    clientEngine = new NullSslEngine();
                } else {
                    clientEngine = createClientSslEngine(cipher, chosenPort);
                }

                ClientTlsChannel clientChannel = ClientTlsChannel.newBuilder(
                                new RandomChunkingByteChannel(rawClient, SocketPairFactory::getChunkingSize),
                                clientEngine)
                        .withWaitForCloseConfirmation(waitForCloseConfirmation)
                        .withPlainBufferAllocator(globalPlainTrackingAllocator)
                        .withRunTasks(runTasks)
                        .withEncryptedBufferAllocator(globalEncryptedTrackingAllocator)
                        .withReleaseBuffers(releaseBuffers)
                        .build();

                ServerTlsChannel.Builder serverChannelBuilder;
                if (cipher.equals(Optional.of(NULL_CIPHER))) {
                    serverChannelBuilder = ServerTlsChannel.newBuilder(
                            new RandomChunkingByteChannel(rawServer, SocketPairFactory::getChunkingSize),
                            new NullSslContext());
                } else {
                    serverChannelBuilder = ServerTlsChannel.newBuilder(
                                    new RandomChunkingByteChannel(rawServer, SocketPairFactory::getChunkingSize),
                                    nameOpt -> sslContextFactory(clientSniHostName, sslContext, nameOpt))
                            .withEngineFactory(ctx -> fixedCipherServerSslEngineFactory(cipher, ctx));
                }

                ServerTlsChannel serverChannel = serverChannelBuilder
                        .withWaitForCloseConfirmation(waitForCloseConfirmation)
                        .withPlainBufferAllocator(globalPlainTrackingAllocator)
                        .withEncryptedBufferAllocator(globalEncryptedTrackingAllocator)
                        .withReleaseBuffers(releaseBuffers)
                        .build();

                AsynchronousTlsChannel clientAsyncChannel =
                        new AsynchronousTlsChannel(channelGroup, clientChannel, rawClient);
                AsynchronousTlsChannel serverAsyncChannel =
                        new AsynchronousTlsChannel(channelGroup, serverChannel, rawServer);

                AsyncSocketGroup clientPair = new AsyncSocketGroup(clientAsyncChannel, clientChannel, rawClient);
                AsyncSocketGroup serverPair = new AsyncSocketGroup(serverAsyncChannel, serverChannel, rawServer);
                pairs.add(new AsyncSocketPair(clientPair, serverPair));
            }
            return pairs;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public String getGlobalAllocationReport() {
        TrackingAllocator plainAlloc = globalPlainTrackingAllocator;
        TrackingAllocator encryptedAlloc = globalEncryptedTrackingAllocator;
        long maxPlain = plainAlloc.maxAllocation();
        long maxEncrypted = encryptedAlloc.maxAllocation();
        long totalPlain = plainAlloc.bytesAllocated();
        long totalEncrypted = encryptedAlloc.bytesAllocated();
        long buffersAllocatedPlain = plainAlloc.buffersAllocated();
        long buffersAllocatedEncrypted = encryptedAlloc.buffersAllocated();
        long buffersDeallocatedPlain = plainAlloc.buffersDeallocated();
        long buffersDeallocatedEncrypted = encryptedAlloc.buffersDeallocated();
        return "Allocation report:\n"
                + String.format("  max allocation (bytes) - plain: %s - encrypted: %s\n", maxPlain, maxEncrypted)
                + String.format("  total allocation (bytes) - plain: %s - encrypted: %s\n", totalPlain, totalEncrypted)
                + String.format(
                        "  buffers allocated - plain: %s - encrypted: %s\n",
                        buffersAllocatedPlain, buffersAllocatedEncrypted)
                + String.format(
                        "  buffers deallocated - plain: %s - encrypted: %s\n",
                        buffersDeallocatedPlain, buffersDeallocatedEncrypted);
    }

    public static void checkDeallocation(SocketPair socketPair) {
        checkBufferDeallocation(socketPair.client.tls.getPlainBufferAllocator());
        checkBufferDeallocation(socketPair.client.tls.getEncryptedBufferAllocator());
    }

    public static void checkDeallocation(AsyncSocketPair socketPair) {
        checkBufferDeallocation(socketPair.client.tls.getPlainBufferAllocator());
        checkBufferDeallocation(socketPair.client.tls.getEncryptedBufferAllocator());
    }

    private static void checkBufferDeallocation(TrackingAllocator allocator) {
        logger.fine(() -> String.format("allocator: %s; allocated: %s}", allocator, allocator.bytesAllocated()));
        logger.fine(() -> String.format("allocator: %s; deallocated: %s", allocator, allocator.bytesDeallocated()));
        assertEquals(allocator.bytesDeallocated(), allocator.bytesAllocated(), " - some buffers were not deallocated");
    }

    private static int getChunkingSize() {
        double labmda = 1.0 / SslContextFactory.tlsMaxDataSize;
        double uniform = new Random().nextDouble();
        double exp = Math.log(uniform) * (-1 / labmda);
        return Math.max((int) exp, 1);
    }
}
