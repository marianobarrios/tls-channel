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

import scala.collection.JavaConverters.seqAsJavaList

case class SocketPair(client: SocketGroup, server: SocketGroup)

case class SocketGroup(external: ByteChannel, tls: TlsChannel, plain: SocketChannel)

/**
 * Create pairs of connected sockets (using the loopback interface).
 * Additionally, all the raw (non-encrypted) socket channel are wrapped with a chunking decorator that partitions
 * the bytesProduced of any read or write operation.
 */
class SocketPairFactory(val sslContext: SSLContext, val serverName: String = SslContextFactory.certificateCommonName) extends StrictLogging {

  private val releaseBuffers = true

  private val clientSniHostName = new SNIHostName(serverName)
  private val expectedSniHostName = SNIHostName.createSNIMatcher(serverName /* regex! */)

  def fixedCipherServerSslEngineFactory(cipher: String)(sslContext: SSLContext): SSLEngine = {
    val engine = sslContext.createSSLEngine()
    engine.setUseClientMode(false)
    engine.setEnabledCipherSuites(Array(cipher))
    engine
  }

  def sslContextFactory(expectedName: SNIServerName, sslContext: SSLContext)(name: Optional[SNIServerName]): Optional[SSLContext] = {
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
    sslParams.setServerNames(seqAsJavaList(Seq(clientSniHostName)))
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
    sslParameters.setServerNames(seqAsJavaList(Seq(clientSniHostName)))
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
    sslParameters.setServerNames(seqAsJavaList(Seq(clientSniHostName)))
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
    waitForCloseConfirmation: Boolean = false): SocketPair = {
    nioNioN(
      cipher,
      1,
      externalClientChunkSize,
      internalClientChunkSize,
      externalServerChunkSize,
      internalServerChunkSize,
      runTasks,
      waitForCloseConfirmation).head
  }

  def nioNioN(
    cipher: String,
    qtty: Int,
    externalClientChunkSize: Option[Int] = None,
    internalClientChunkSize: Option[Int] = None,
    externalServerChunkSize: Option[Int] = None,
    internalServerChunkSize: Option[Int] = None,
    runTasks: Boolean = true,
    waitForCloseConfirmation: Boolean = false): Seq[SocketPair] = {
    val serverSocket = ServerSocketChannel.open()
    try {
      serverSocket.bind(new InetSocketAddress(localhost, 0 /* find free port */ ))
      val chosenPort = serverSocket.getLocalAddress.asInstanceOf[InetSocketAddress].getPort
      val address = new InetSocketAddress(localhost, chosenPort)
      for (i <- 0 until qtty) yield {
        val rawClient = SocketChannel.open(address)
        val rawServer = serverSocket.accept()

        val plainClient = externalClientChunkSize match {
          case Some(size) => new ChunkingByteChannel(rawClient, chunkSize = size)
          case None => rawClient
        }
        val plainServer = internalServerChunkSize match {
          case Some(size) => new ChunkingByteChannel(rawServer, chunkSize = size)
          case None => rawServer
        }

        val clientChannel = ClientTlsChannel
          .newBuilder(plainClient, createClientSslEngine(cipher, chosenPort))
          .withRunTasks(runTasks)
          .withWaitForCloseConfirmation(waitForCloseConfirmation)
          .withPlainBufferAllocator(globalPlainTrackingAllocator)
          .withEncryptedBufferAllocator(globalEncryptedTrackingAllocator)
          .withReleaseBuffers(releaseBuffers)
          .build()
        val serverChannel = ServerTlsChannel
          .newBuilder(plainServer, nameOpt => sslContextFactory(clientSniHostName, sslContext)(nameOpt))
          .withEngineFactory(fixedCipherServerSslEngineFactory(cipher) _)
          .withRunTasks(runTasks)
          .withWaitForCloseConfirmation(waitForCloseConfirmation)
          .withPlainBufferAllocator(globalPlainTrackingAllocator)
          .withEncryptedBufferAllocator(globalEncryptedTrackingAllocator)
          .withReleaseBuffers(releaseBuffers)
          .build()

        val externalClient = externalClientChunkSize match {
          case Some(size) => new ChunkingByteChannel(clientChannel, chunkSize = size)
          case None => clientChannel
        }
        val externalServer = externalClientChunkSize match {
          case Some(size) => new ChunkingByteChannel(serverChannel, chunkSize = size)
          case None => serverChannel
        }

        val clientPair = SocketGroup(externalClient, clientChannel, rawClient)
        val serverPair = SocketGroup(externalServer, serverChannel, rawServer)
        SocketPair(clientPair, serverPair)
      }
    } finally {
      serverSocket.close()
    }
  }

  def nullNioNio(internalClientChunkSize: Option[Int], internalServerChunkSize: Option[Int],
    encryptedBufferAllocator: BufferAllocator): SocketPair = {
    nullNioNioN(1, internalClientChunkSize, internalServerChunkSize, encryptedBufferAllocator).head
  }

  def nullNioNioN(
    qtty: Int,
    internalClientChunkSize: Option[Int],
    internalServerChunkSize: Option[Int],
    encryptedBufferAllocator: BufferAllocator = globalEncryptedTrackingAllocator): Seq[SocketPair] = {
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
          case None => rawClient
        }
        val plainServer = internalServerChunkSize match {
          case Some(size) => new ChunkingByteChannel(rawServer, chunkSize = size)
          case None => rawServer
        }

        val clientChannel = ClientTlsChannel.newBuilder(plainClient, new NullSslEngine)
          .withPlainBufferAllocator(globalPlainTrackingAllocator)
          .withEncryptedBufferAllocator(encryptedBufferAllocator)
          .withReleaseBuffers(releaseBuffers)
          .build()
        val serverChannel = ServerTlsChannel.newBuilder(plainServer, new NullSslContext)
          .withPlainBufferAllocator(globalPlainTrackingAllocator)
          .withEncryptedBufferAllocator(encryptedBufferAllocator)
          .withReleaseBuffers(releaseBuffers)
          .build()

        SocketPair(SocketGroup(clientChannel, clientChannel, rawClient), SocketGroup(serverChannel, serverChannel, rawServer))
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

  private def checkBufferDeallocation(allocator: TrackingAllocator) = {
    logger.debug(s"allocator: ${allocator}; allocated: ${allocator.bytesAllocated()}")
    logger.debug(s"allocator: ${allocator}; deallocated: ${allocator.bytesDeallocated()}")
    assert(allocator.bytesAllocated() == allocator.bytesDeallocated(), " - some buffers were not deallocated")
  }

}
