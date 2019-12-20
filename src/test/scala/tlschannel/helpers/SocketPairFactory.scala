package tlschannel.helpers

import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLEngine

import com.typesafe.scalalogging.StrictLogging
import javax.crypto.Cipher
import java.nio.channels.ByteChannel
import java.util.Optional

import sun.security.ssl.SSLSocketImpl
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName

import tlschannel._
import tlschannel.async.AsynchronousTlsChannel
import tlschannel.async.AsynchronousTlsChannelGroup
import tlschannel.async.ExtendedAsynchronousByteChannel

import scala.jdk.CollectionConverters._
import scala.util.Random

case class SocketPair(client: SocketGroup, server: SocketGroup)

case class AsyncSocketPair(client: AsyncSocketGroup, server: AsyncSocketGroup)

case class SocketGroup(external: ByteChannel, tls: TlsChannel, plain: SocketChannel)

case class AsyncSocketGroup(external: ExtendedAsynchronousByteChannel, tls: TlsChannel, plain: SocketChannel)

/**
  * Create pairs of connected sockets (using the loopback interface).
  * Additionally, all the raw (non-encrypted) socket channel are wrapped with a chunking decorator that partitions
  * the bytesProduced of any read or write operation.
  */
class SocketPairFactory(
    val sslContext: SSLContext,
    val serverName: String = SslContextFactory.certificateCommonName
) extends StrictLogging {

  private val releaseBuffers = true

  private val clientSniHostName = new SNIHostName(serverName)
  private val expectedSniHostName = SNIHostName.createSNIMatcher(serverName /* regex! */ )

  def fixedCipherServerSslEngineFactory(cipher: String)(sslContext: SSLContext): SSLEngine = {
    val engine = sslContext.createSSLEngine()
    engine.setUseClientMode(false)
    engine.setEnabledCipherSuites(Array(cipher))
    engine
  }

  def sslContextFactory(expectedName: SNIServerName, sslContext: SSLContext)(
      name: Optional[SNIServerName]
  ): Optional[SSLContext] = {
    if (name.isPresent) {
      val n = name.get
      logger.debug("ContextFactory, requested name: " + n)
      if (!expectedSniHostName.matches(n)) {
        throw new IllegalArgumentException(s"Received SNI $n does not match $serverName")
      }
      Optional.of(sslContext)
    } else {
      throw new IllegalArgumentException("SNI expected")
    }
  }

  val localhost = InetAddress.getByName(null)

  logger.info(s"AES max key length: ${Cipher.getMaxAllowedKeyLength("AES")}")

  val sslSocketFactory = sslContext.getSocketFactory
  val sslServerSocketFactory = sslContext.getServerSocketFactory

  val globalPlainTrackingAllocator = new TrackingAllocator(TlsChannel.defaultPlainBufferAllocator)
  val globalEncryptedTrackingAllocator = new TrackingAllocator(TlsChannel.defaultEncryptedBufferAllocator)

  private def createClientSslEngine(cipher: String, peerPort: Integer): SSLEngine = {
    val engine = sslContext.createSSLEngine(serverName, peerPort)
    engine.setUseClientMode(true)
    engine.setEnabledCipherSuites(Array(cipher))
    val sslParams = engine.getSSLParameters() // returns a value object
    sslParams.setEndpointIdentificationAlgorithm("HTTPS")
    sslParams.setServerNames(Seq[SNIServerName](clientSniHostName).asJava)
    engine.setSSLParameters(sslParams)
    engine
  }

  private def createSslServerSocket(cipher: String): SSLServerSocket = {
    val serverSocket = sslServerSocketFactory.createServerSocket(0 /* find free port */ ).asInstanceOf[SSLServerSocket]
    serverSocket.setEnabledCipherSuites(Array(cipher))
    serverSocket
  }

  private def createSslSocket(cipher: String, host: InetAddress, port: Int, requestedHost: String): SSLSocket = {
    val socket = sslSocketFactory.createSocket(host, port).asInstanceOf[SSLSocketImpl]
    socket.setEnabledCipherSuites(Array(cipher))
    socket.setHost(requestedHost)
    socket
  }

  def oldOld(cipher: String): (SSLSocket, SSLSocket) = {
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

  def oldNio(cipher: String): (SSLSocket, SocketGroup) = {
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

  def nioOld(cipher: String): (SocketGroup, SSLSocket) = {
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
      cipher: String,
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
      cipher: String,
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
      for (i <- 0 until qtty) yield {
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
        val externalServer = externalClientChunkSize match {
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

  def asyncN(
      cipher: String,
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
      for (i <- 0 until qtty) yield {
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

  def printGlobalAllocationReport() = {
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
    println(s"Allocation report:")
    println(s"  max allocation (bytes) - plain: $maxPlain - encrypted: $maxEncrypted")
    println(s"  total allocation (bytes) - plain: $totalPlain - encrypted: $totalEncrypted")
    println(s"  buffers allocated - plain: $buffersAllocatedPlain - encrypted: $buffersAllocatedEncrypted")
    println(s"  buffers deallocated - plain: $buffersDeallocatedPlain - encrypted: $buffersDeallocatedEncrypted")
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
