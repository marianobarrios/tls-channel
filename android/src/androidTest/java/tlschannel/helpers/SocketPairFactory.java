package tlschannel.helpers

import java.net.UnknownHostException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import javax.net.SocketFactory;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLEngine;

import javax.crypto.Cipher;
import java.nio.channels.ByteChannel;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLSocketFactory;

import tlschannel.*;
import tlschannel.async.AsynchronousTlsChannel;
import tlschannel.async.AsynchronousTlsChannelGroup;
import tlschannel.async.ExtendedAsynchronousByteChannel;

case class SocketPair(client: SocketGroup, server: SocketGroup)

case class AsyncSocketPair(client: AsyncSocketGroup, server: AsyncSocketGroup)

case class SocketGroup(external: ByteChannel, tls: TlsChannel, plain: SocketChannel)

case class AsyncSocketGroup(external: ExtendedAsynchronousByteChannel, tls: TlsChannel, plain: SocketChannel)

/** Create pairs of connected sockets (using the loopback interface). Additionally, all the raw (non-encrypted) socket
  * channel are wrapped with a chunking decorator that partitions the bytesProduced of any read or write operation.
  */
class SocketPairFactory {
  private final SSLContext sslContext;
  private final String serverName;

  public SocketPairFactory(SSLContext sslContext) throws UnknownHostException {
    this(sslContext, SslContextFactory.certificateCommonName);
  }

  public SocketPairFactory(SSLContext sslContext, String serverName) throws UnknownHostException {
    this.sslContext = sslContext;
    this.serverName = serverName;
  }

  private final boolean releaseBuffers = true;

  private final SNIHostName clientSniHostName = new SNIHostName(serverName);
  private final SNIMatcher expectedSniHostName = SNIHostName.createSNIMatcher(serverName /* regex! */ );

  public SSLEngine fixedCipherServerSslEngineFactory(Optional<String[]> cipher) {
    SSLEngine engine = sslContext.createSSLEngine();
    engine.setUseClientMode(false);
    if (cipher.isPresent()) {
      engine.setEnabledCipherSuites(cipher.orElse(new String[0]));
    }
    return engine;
  }

  public Optional<SSLContext> sslContextFactory(Optional<SNIServerName> name) {
    if (name.isPresent()) {
      SNIServerName n = name.get();
      logger.debug("ContextFactory, requested name: " + n);
      if (!expectedSniHostName.matches(n)) {
        throw new IllegalArgumentException("Received SNI "+n+" does not match "+serverName);
      }
      return Optional.of(sslContext);
    } else {
      throw new IllegalArgumentException("SNI expected");
    }
  }

  final InetAddress localhost = InetAddress.getByName(null);

  static {
    try {
      logger.info("AES max key length: " + Cipher.getMaxAllowedKeyLength("AES"));
    } catch (NoSuchAlgorithmException e) {
      e.printStackTrace();
    }
  }

  final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();
  final SSLServerSocketFactory sslServerSocketFactory = sslContext.getServerSocketFactory();

  final TrackingAllocator globalPlainTrackingAllocator = new TrackingAllocator(TlsChannel.defaultPlainBufferAllocator);
  final TrackingAllocator globalEncryptedTrackingAllocator = new TrackingAllocator(TlsChannel.defaultEncryptedBufferAllocator);

  public SSLEngine createClientSslEngine(Optional<String[]> cipher, Integer peerPort) {
    SSLEngine engine = sslContext.createSSLEngine(serverName, peerPort);
    engine.setUseClientMode(true);
    if (cipher.isPresent()) {
      engine.setEnabledCipherSuites(cipher.orElse(new String[0]));
    }
    SSLParameters sslParams = engine.getSSLParameters(); // returns a value object
    sslParams.setEndpointIdentificationAlgorithm("HTTPS");
    sslParams.setServerNames(Arrays.asList(clientSniHostName));
    engine.setSSLParameters(sslParams);
    return engine;
  }

  private SSLServerSocket createSslServerSocket(Optional<String[]> cipher) {
    SSLServerSocket serverSocket = (SSLServerSocket[])sslServerSocketFactory.createServerSocket(0 /* find free port */ );
    if (cipher.isPresent()) {
      serverSocket.setEnabledCipherSuites(cipher.orElse(new String[0]));
    }
    return serverSocket;
  }

  private SSLSocket createSslSocket(
      Optional<String[]> cipher,
      InetAddress host,
      int port,
      String requestedHost
  ) {
    SSLSocket socket = (SSLSocket)sslSocketFactory.createSocket(host, port);
    if (cipher.isPresent()) {
      socket.setEnabledCipherSuites(cipher.orElse(new String[0]));
    }
    return socket;
  }

  def oldOld(cipher: Option[String] = None): (SSLSocket, SSLSocket) = {
    val serverSocket = createSslServerSocket(cipher)
    val chosenPort = serverSocket.getLocalPort
    val client = createSslSocket(cipher, localhost, chosenPort, requestedHost = serverName)
    val sslParameters = client.getSSLParameters // returns a value object
    sslParameters.setServerNames(Seq[SNIServerName](clientSniHostName).asJava)
    client.setSSLParameters(sslParameters)
    val server = serverSocket.accept().asInstanceOf[SSLSocket]
    serverSocket.close()
    (client, server)
  }

  def oldNio(cipher: Option[String] = None): (SSLSocket, SocketGroup) = {
    val serverSocket = ServerSocketChannel.open()
    serverSocket.bind(new InetSocketAddress(localhost, 0 /* find free port */ ))
    val chosenPort = serverSocket.getLocalAddress.asInstanceOf[InetSocketAddress].getPort
    val client = createSslSocket(cipher, localhost, chosenPort, requestedHost = serverName)
    val sslParameters = client.getSSLParameters // returns a value object
    sslParameters.setServerNames(Seq[SNIServerName](clientSniHostName).asJava)
    client.setSSLParameters(sslParameters)
    val rawServer = serverSocket.accept()
    serverSocket.close()
    val server = ServerTlsChannel
      .newBuilder(rawServer, nameOpt => sslContextFactory(clientSniHostName, sslContext)(nameOpt))
      .withEngineFactory(fixedCipherServerSslEngineFactory(cipher) _)
      .build()
    (client, SocketGroup(server, server, rawServer))
  }

  def nioOld(cipher: Option[String] = None): (SocketGroup, SSLSocket) = {
    val serverSocket = createSslServerSocket(cipher)
    val chosenPort = serverSocket.getLocalPort
    val address = new InetSocketAddress(localhost, chosenPort)
    val rawClient = SocketChannel.open(address)
    val server = serverSocket.accept().asInstanceOf[SSLSocket]
    serverSocket.close()
    val client = ClientTlsChannel
      .newBuilder(rawClient, createClientSslEngine(cipher, chosenPort))
      .build()
    (SocketGroup(client, client, rawClient), server)
  }

  def nioNio(
      cipher: Option[String] = None,
      externalClientChunkSize: Option[Int] = None,
      internalClientChunkSize: Option[Int] = None,
      externalServerChunkSize: Option[Int] = None,
      internalServerChunkSize: Option[Int] = None,
      runTasks: Boolean = true,
      waitForCloseConfirmation: Boolean = false,
      pseudoAsyncGroup: Option[AsynchronousTlsChannelGroup] = None
  ): SocketPair = {
    nioNioN(
      cipher,
      1,
      externalClientChunkSize,
      internalClientChunkSize,
      externalServerChunkSize,
      internalServerChunkSize,
      runTasks,
      waitForCloseConfirmation,
      pseudoAsyncGroup
    ).head
  }

  def nioNioN(
      cipher: Option[String] = None,
      qtty: Int,
      externalClientChunkSize: Option[Int] = None,
      internalClientChunkSize: Option[Int] = None,
      externalServerChunkSize: Option[Int] = None,
      internalServerChunkSize: Option[Int] = None,
      runTasks: Boolean = true,
      waitForCloseConfirmation: Boolean = false,
      pseudoAsyncGroup: Option[AsynchronousTlsChannelGroup] = None
  ): Seq[SocketPair] = {
    val serverSocket = ServerSocketChannel.open()
    try {
      serverSocket.bind(new InetSocketAddress(localhost, 0 /* find free port */ ))
      val chosenPort = serverSocket.getLocalAddress.asInstanceOf[InetSocketAddress].getPort
      val address = new InetSocketAddress(localhost, chosenPort)
      for (_ <- 0 until qtty) yield {
        val rawClient = SocketChannel.open(address)
        val rawServer = serverSocket.accept()

        val plainClient = internalClientChunkSize match {
          case Some(size) => new ChunkingByteChannel(rawClient, chunkSize = size)
          case None       => rawClient
        }

        val plainServer = internalServerChunkSize match {
          case Some(size) => new ChunkingByteChannel(rawServer, chunkSize = size)
          case None       => rawServer
        }

        val clientEngine = if (cipher == null) {
          new NullSslEngine
        } else {
          createClientSslEngine(cipher, chosenPort)
        }

        val clientChannel = ClientTlsChannel
          .newBuilder(plainClient, clientEngine)
          .withRunTasks(runTasks)
          .withWaitForCloseConfirmation(waitForCloseConfirmation)
          .withPlainBufferAllocator(globalPlainTrackingAllocator)
          .withEncryptedBufferAllocator(globalEncryptedTrackingAllocator)
          .withReleaseBuffers(releaseBuffers)
          .build()

        val serverChannelBuilder = if (cipher == null) {
          ServerTlsChannel
            .newBuilder(plainServer, new NullSslContext)
        } else {
          ServerTlsChannel
            .newBuilder(plainServer, nameOpt => sslContextFactory(clientSniHostName, sslContext)(nameOpt))
            .withEngineFactory(fixedCipherServerSslEngineFactory(cipher) _)
        }

        val serverChannel = serverChannelBuilder
          .withRunTasks(runTasks)
          .withWaitForCloseConfirmation(waitForCloseConfirmation)
          .withPlainBufferAllocator(globalPlainTrackingAllocator)
          .withEncryptedBufferAllocator(globalEncryptedTrackingAllocator)
          .withReleaseBuffers(releaseBuffers)
          .build()

        /*
         * Handler executor can be null because BlockerByteChannel will only use Futures, never callbacks.
         */

        val clientAsyncChannel = pseudoAsyncGroup match {
          case Some(channelGroup) =>
            rawClient.configureBlocking(false)
            new BlockerByteChannel(new AsynchronousTlsChannel(channelGroup, clientChannel, rawClient))
          case None =>
            clientChannel
        }

        val serverAsyncChannel = pseudoAsyncGroup match {
          case Some(channelGroup) =>
            rawServer.configureBlocking(false)
            new BlockerByteChannel(new AsynchronousTlsChannel(channelGroup, serverChannel, rawServer))
          case None =>
            serverChannel
        }

        val externalClient = externalClientChunkSize match {
          case Some(size) => new ChunkingByteChannel(clientAsyncChannel, chunkSize = size)
          case None       => clientChannel
        }
        val externalServer = externalServerChunkSize match {
          case Some(size) => new ChunkingByteChannel(serverAsyncChannel, chunkSize = size)
          case None       => serverChannel
        }

        val clientPair = SocketGroup(externalClient, clientChannel, rawClient)
        val serverPair = SocketGroup(externalServer, serverChannel, rawServer)
        SocketPair(clientPair, serverPair)
      }
    } finally {
      serverSocket.close()
    }
  }

  def async(
      cipher: Option[String] = None,
      channelGroup: AsynchronousTlsChannelGroup,
      runTasks: Boolean,
      waitForCloseConfirmation: Boolean = false
  ): AsyncSocketPair = {
    asyncN(cipher, channelGroup, 1, runTasks, waitForCloseConfirmation).head
  }

  def asyncN(
      cipher: Option[String] = None,
      channelGroup: AsynchronousTlsChannelGroup,
      qtty: Int,
      runTasks: Boolean,
      waitForCloseConfirmation: Boolean = false
  ): Seq[AsyncSocketPair] = {
    val serverSocket = ServerSocketChannel.open()

    try {
      serverSocket.bind(new InetSocketAddress(localhost, 0 /* find free port */ ))
      val chosenPort = serverSocket.getLocalAddress.asInstanceOf[InetSocketAddress].getPort
      val address = new InetSocketAddress(localhost, chosenPort)
      for (_ <- 0 until qtty) yield {
        val rawClient = SocketChannel.open(address)
        val rawServer = serverSocket.accept()

        rawClient.configureBlocking(false)
        rawServer.configureBlocking(false)

        val clientEngine = if (cipher == null) {
          new NullSslEngine
        } else {
          createClientSslEngine(cipher, chosenPort)
        }

        val clientChannel = ClientTlsChannel
          .newBuilder(new RandomChunkingByteChannel(rawClient, SocketPairFactory.getChunkingSize _), clientEngine)
          .withWaitForCloseConfirmation(waitForCloseConfirmation)
          .withPlainBufferAllocator(globalPlainTrackingAllocator)
          .withRunTasks(runTasks)
          .withEncryptedBufferAllocator(globalEncryptedTrackingAllocator)
          .withReleaseBuffers(releaseBuffers)
          .build()

        val serverChannelBuilder = if (cipher == null) {
          ServerTlsChannel
            .newBuilder(
              new RandomChunkingByteChannel(rawServer, SocketPairFactory.getChunkingSize _),
              new NullSslContext
            )
        } else {
          ServerTlsChannel
            .newBuilder(
              new RandomChunkingByteChannel(rawServer, SocketPairFactory.getChunkingSize _),
              nameOpt => sslContextFactory(clientSniHostName, sslContext)(nameOpt)
            )
            .withEngineFactory(fixedCipherServerSslEngineFactory(cipher))
        }

        val serverChannel = serverChannelBuilder
          .withWaitForCloseConfirmation(waitForCloseConfirmation)
          .withPlainBufferAllocator(globalPlainTrackingAllocator)
          .withEncryptedBufferAllocator(globalEncryptedTrackingAllocator)
          .withReleaseBuffers(releaseBuffers)
          .build()

        val clientAsyncChannel = new AsynchronousTlsChannel(channelGroup, clientChannel, rawClient)
        val serverAsyncChannel = new AsynchronousTlsChannel(channelGroup, serverChannel, rawServer)

        val clientPair = AsyncSocketGroup(clientAsyncChannel, clientChannel, rawClient)
        val serverPair = AsyncSocketGroup(serverAsyncChannel, serverChannel, rawServer)
        AsyncSocketPair(clientPair, serverPair)
      }
    } finally {
      serverSocket.close()
    }
  }

  def getGlobalAllocationReport(): String = {
    val plainAlloc = globalPlainTrackingAllocator
    val encryptedAlloc = globalEncryptedTrackingAllocator
    val maxPlain = plainAlloc.maxAllocation()
    val maxEncrypted = encryptedAlloc.maxAllocation()
    val totalPlain = plainAlloc.bytesAllocated()
    val totalEncrypted = encryptedAlloc.bytesAllocated()
    val buffersAllocatedPlain = plainAlloc.buffersAllocated()
    val buffersAllocatedEncrypted = encryptedAlloc.buffersAllocated()
    val buffersDeallocatedPlain = plainAlloc.buffersDeallocated()
    val buffersDeallocatedEncrypted = encryptedAlloc.buffersDeallocated()
    val ret = new StringBuilder
    ret ++= s"Allocation report:\n"
    ret ++= s"  max allocation (bytes) - plain: $maxPlain - encrypted: $maxEncrypted\n"
    ret ++= s"  total allocation (bytes) - plain: $totalPlain - encrypted: $totalEncrypted\n"
    ret ++= s"  buffers allocated - plain: $buffersAllocatedPlain - encrypted: $buffersAllocatedEncrypted\n"
    ret ++= s"  buffers deallocated - plain: $buffersDeallocatedPlain - encrypted: $buffersDeallocatedEncrypted\n"
    ret.toString()
  }

}

object SocketPairFactory extends StrictLogging {

  def checkDeallocation(socketPair: SocketPair) = {
    checkBufferDeallocation(socketPair.client.tls.getPlainBufferAllocator)
    checkBufferDeallocation(socketPair.client.tls.getEncryptedBufferAllocator)
  }

  def checkDeallocation(socketPair: AsyncSocketPair) = {
    checkBufferDeallocation(socketPair.client.tls.getPlainBufferAllocator)
    checkBufferDeallocation(socketPair.client.tls.getEncryptedBufferAllocator)
  }

  private def checkBufferDeallocation(allocator: TrackingAllocator) = {
    logger.debug(s"allocator: ${allocator}; allocated: ${allocator.bytesAllocated()}")
    logger.debug(s"allocator: ${allocator}; deallocated: ${allocator.bytesDeallocated()}")
    assert(allocator.bytesAllocated() == allocator.bytesDeallocated(), " - some buffers were not deallocated")
  }

  def getChunkingSize(): Int = {
    val labmda = 1.0 / SslContextFactory.tlsMaxDataSize
    val uniform = Random.nextDouble()
    val exp = math.log(uniform) * (-1 / labmda)
    math.max(exp.toInt, 1)
  }

}
