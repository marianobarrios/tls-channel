package tlschannel

import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.net.InetAddress
import java.net.InetSocketAddress
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLServerSocketFactory
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLEngine
import java.security.KeyStore
import java.io.FileInputStream
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.KeyManagerFactory
import com.typesafe.scalalogging.slf4j.StrictLogging
import javax.crypto.Cipher
import javax.net.ssl.SSLParameters
import tlschannel.helpers.TestUtil.fnToFunction
import tlschannel.helpers.TestUtil.fnToConsumer
import javax.net.ssl.SSLSession
import java.nio.channels.ByteChannel
import java.util.Optional
import sun.security.ssl.SSLSocketImpl
import tlschannel.util.Util;

import javax.net.ssl.SNIHostName
import scala.collection.JavaConversions._
import tlschannel.helpers.TestUtil
import tlschannel.helpers.ChunkingByteChannel

case class SocketPair(client: SocketGroup, server: SocketGroup)

case class SocketGroup(external: ByteChannel, tls: TlsSocketChannel, plain: SocketChannel)

/**
 * Create pairs of connected sockets (using the loopback interface).
 * Additionally, all the raw (non-encrypted) socket channel are wrapped with a chunking decorator that partitions
 * the bytes of any read or write operation.
 */
class SocketPairFactory(val sslContext: SSLContext, val serverName: String) extends StrictLogging {

  def fixedCipherServerSslEngineFactory(cipher: String)(sslContext: SSLContext): SSLEngine = {
    val engine = sslContext.createSSLEngine()
    engine.setUseClientMode(false)
    engine.setEnabledCipherSuites(Array(cipher))
    engine
  }
  
  def sslContextFactory(expectedName: String, sslContext: SSLContext)(name: Optional[String]): SSLContext = {
    name.ifPresent { (n: String) => 
      logger.debug("ContextFactory, requested name: " + n)
      Util.assertTrue(n == expectedName)
    }
    sslContext
  }

  val localhost = InetAddress.getByName(null)

  logger.info(s"AES max key length: ${Cipher.getMaxAllowedKeyLength("AES")}")

  val sslSocketFactory = sslContext.getSocketFactory
  val sslServerSocketFactory = sslContext.getServerSocketFactory

  private def createClientSslEngine(cipher: String, peerHost: String, peerPort: Integer): SSLEngine = {
    val engine = sslContext.createSSLEngine(peerHost, peerPort)
    engine.setUseClientMode(true)
    engine.setEnabledCipherSuites(Array(cipher))
    val sslParams = engine.getSSLParameters() // returns a value object
    sslParams.setEndpointIdentificationAlgorithm("HTTPS")
    sslParams.setServerNames(Seq(new SNIHostName(serverName)))
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

  def oldNio(cipher: String): (SSLSocket, SocketGroup) = {
    val serverSocket = ServerSocketChannel.open()
    serverSocket.bind(new InetSocketAddress(localhost, 0 /* find free port */ ))
    val chosenPort = serverSocket.getLocalAddress.asInstanceOf[InetSocketAddress].getPort
    val client = createSslSocket(cipher, localhost, chosenPort, requestedHost = serverName)
    val rawServer = serverSocket.accept()
    serverSocket.close()
    val server = new TlsServerSocketChannel.Builder(rawServer, sslContextFactory(serverName, sslContext) _)
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
    val client = new TlsClientSocketChannel.Builder(rawClient, createClientSslEngine(cipher, serverName, chosenPort)).build()
    (SocketGroup(client, client, rawClient), server)
  }

  def nioNio(cipher: String): SocketPair = {
    nioNioN(cipher, 1, None, None, None, None).head
  }

  def nioNioN(
    cipher: String,
    qtty: Int,
    externalClientChunkSize: Option[Int],
    internalClientChunkSize: Option[Int],
    externalServerChunkSize: Option[Int],
    internalServerChunkSize: Option[Int],
    runTasks: Boolean = true): Seq[SocketPair] = {
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

        val clientChannel = new TlsClientSocketChannel.Builder(plainClient, createClientSslEngine(cipher, serverName, chosenPort))
          .withRunTasks(runTasks)
          .build()
        val serverChannel = new TlsServerSocketChannel.Builder(plainServer, sslContextFactory(serverName, sslContext) _)
          .withEngineFactory(fixedCipherServerSslEngineFactory(cipher) _)
          .withRunTasks(runTasks)
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

  def nioNio(
    cipher: String,
    externalClientChunkSize: Option[Int],
    internalClientChunkSize: Option[Int],
    externalServerChunkSize: Option[Int],
    internalServerChunkSize: Option[Int]): SocketPair = {
    nioNioN(cipher, 1, externalClientChunkSize, internalClientChunkSize, externalServerChunkSize, internalServerChunkSize).head
  }

}
