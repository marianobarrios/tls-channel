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
import TestUtil.fnToFunction
import TestUtil.fnToConsumer
import javax.net.ssl.SSLSession

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
    engine.setSSLParameters(sslParams)
    engine
  }

  private def createSslServerSocket(cipher: String): SSLServerSocket = {
    val serverSocket = sslServerSocketFactory.createServerSocket(0 /* find free port */ ).asInstanceOf[SSLServerSocket]
    serverSocket.setEnabledCipherSuites(Array(cipher))
    serverSocket
  }

  private def createSslSocket(cipher: String, host: InetAddress, port: Int): SSLSocket = {
    val socket = sslSocketFactory.createSocket(host, port).asInstanceOf[SSLSocket]
    socket.setEnabledCipherSuites(Array(cipher))
    socket
  }

  def oldNio(cipher: String) = {
    val serverSocket = ServerSocketChannel.open()
    serverSocket.bind(new InetSocketAddress(localhost, 0 /* find free port */ ))
    val chosenPort = serverSocket.getLocalAddress.asInstanceOf[InetSocketAddress].getPort
    val client = createSslSocket(cipher, localhost, chosenPort)
    val rawServer = serverSocket.accept()
    serverSocket.close()
    val server = new TlsServerSocketChannel.Builder(rawServer, sslContext)
      .withEngineFactory(fixedCipherServerSslEngineFactory(cipher) _)
      .build()
    (client, (server, rawServer))
  }

  def nioOld(cipher: String) = {
    val serverSocket = createSslServerSocket(cipher)
    val chosenPort = serverSocket.getLocalPort
    val address = new InetSocketAddress(localhost, chosenPort)
    val rawClient = SocketChannel.open(address)
    val server = serverSocket.accept().asInstanceOf[SSLSocket]
    serverSocket.close()
    val client = new TlsClientSocketChannel.Builder(rawClient, createClientSslEngine(cipher, serverName, chosenPort)).build()
    ((client, rawClient), server)
  }

  def nioNio(cipher: String) = {
    val serverSocket = ServerSocketChannel.open()
    serverSocket.bind(new InetSocketAddress(localhost, 0 /* find free port */ ))
    val chosenPort = serverSocket.getLocalAddress.asInstanceOf[InetSocketAddress].getPort
    val address = new InetSocketAddress(localhost, chosenPort)
    val rawClient = SocketChannel.open(address)
    val rawServer = serverSocket.accept()
    serverSocket.close()
    val clientChannel = new TlsClientSocketChannel.Builder(rawClient, createClientSslEngine(cipher, serverName, chosenPort))
      .build()
    val serverChannel = new TlsServerSocketChannel.Builder(rawServer, sslContext)
      .withEngineFactory(fixedCipherServerSslEngineFactory(cipher) _)
      .build()
    ((clientChannel, rawClient), (serverChannel, rawServer))
  }

  def nioNio(
    cipher: String,
    externalClientChunkSize: Int,
    internalClientChunkSize: Int,
    externalServerChunkSize: Int,
    internalServerChunkSize: Int) = {
    val serverSocket = ServerSocketChannel.open()
    serverSocket.bind(new InetSocketAddress(localhost, 0 /* find free port */ ))
    val chosenPort = serverSocket.getLocalAddress.asInstanceOf[InetSocketAddress].getPort
    val address = new InetSocketAddress(localhost, chosenPort)
    val rawClient = SocketChannel.open(address)
    val rawServer = serverSocket.accept()
    serverSocket.close()
    val plainClient = new ChunkingByteChannel(rawClient, chunkSize = externalClientChunkSize)
    val plainServer = new ChunkingByteChannel(rawServer, chunkSize = externalServerChunkSize)
    val clientChannel = new TlsClientSocketChannel.Builder(plainClient, createClientSslEngine(cipher, serverName, chosenPort))
      .build()
    val serverChannel = new TlsServerSocketChannel.Builder(plainServer, sslContext)
      .withEngineFactory(fixedCipherServerSslEngineFactory(cipher) _)
      .build()
    val clientPair = (new ChunkingByteChannel(clientChannel, chunkSize = externalClientChunkSize), clientChannel)
    val serverPair = (new ChunkingByteChannel(serverChannel, chunkSize = externalClientChunkSize), serverChannel)
    (clientPair, serverPair)
  }

}