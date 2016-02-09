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

/**
 * Create pairs of connected sockets (using the loopback interface).
 * Additionally, all the raw (non-encrypted) socket channel are wrapped with a chunking decorator that partitions 
 * the bytes of any read or write operation.
 */
class SocketPairFactory(val port: Int) {

  val localhost = InetAddress.getByName(null)
  
  val address = new InetSocketAddress(localhost, port)

  val sslContext = SSLContext.getDefault
  val sslSocketFactory = SSLSocketFactory.getDefault
  val sslServerSocketFactory = SSLServerSocketFactory.getDefault

  private def createSslEngine(cipher: String, client: Boolean): SSLEngine = {
    val engine = sslContext.createSSLEngine()
    engine.setUseClientMode(client)
    engine.setEnabledCipherSuites(Array(cipher))
    engine
  }

  private def createSslServerSocket(cipher: String, port: Int): SSLServerSocket = {
    val serverSocket = sslServerSocketFactory.createServerSocket(port).asInstanceOf[SSLServerSocket]
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
    serverSocket.bind(address)
    val client = createSslSocket(cipher, localhost, port)
    val rawServer = serverSocket.accept()
    serverSocket.close()
    val server = new TlsServerSocketChannel(
        new RandomizedChunkingByteChannel(rawServer), n => sslContext, e => e.setEnabledCipherSuites(Array(cipher)))
    (client, (server, rawServer))
  }

  def nioNio(cipher: String) = {
    val serverSocket = ServerSocketChannel.open()
    serverSocket.bind(address)
    val rawClient = SocketChannel.open(address)
    val rawServer = serverSocket.accept()
    serverSocket.close()
    val (plainClient, plainServer) = (new RandomizedChunkingByteChannel(rawClient), new RandomizedChunkingByteChannel(rawServer))
    val client = new TlsClientSocketChannel(rawClient, createSslEngine(cipher, client = true))
    val server = new TlsServerSocketChannel(rawServer, n => sslContext, e => e.setEnabledCipherSuites(Array(cipher)))
    ((client, rawClient), (server, rawServer))
  }

  def nioOld(cipher: String) = {
    val serverSocket = createSslServerSocket(cipher, port)
    val rawClient = SocketChannel.open(address)
    val server = serverSocket.accept().asInstanceOf[SSLSocket]
    serverSocket.close()
    val client = new TlsClientSocketChannel(new RandomizedChunkingByteChannel(rawClient), createSslEngine(cipher, client = true))
    ((client, rawClient), server)
  }
  
  def nioNio(
    cipher: String,
    externalClientChunkSize: Int,
    internalClientChunkSize: Int,
    externalServerChunkSize: Int,
    internalServerChunkSize: Int) = {
    val serverSocket = ServerSocketChannel.open()
    serverSocket.bind(address)
    val rawClient = SocketChannel.open(address)
    val rawServer = serverSocket.accept()
    serverSocket.close()
    val plainClient = new ChunkingByteChannel(rawClient, chunkSize = externalClientChunkSize)
    val plainServer = new ChunkingByteChannel(rawServer, chunkSize = externalServerChunkSize)
    val clientChannel = new TlsClientSocketChannel(rawClient, createSslEngine(cipher, client = true))
    val serverChannel = new TlsServerSocketChannel(rawServer, n => sslContext, e => e.setEnabledCipherSuites(Array(cipher)))
    val clientPair = (new ChunkingByteChannel(clientChannel, chunkSize = externalClientChunkSize), clientChannel)
    val serverPair = (new ChunkingByteChannel(serverChannel, chunkSize = externalClientChunkSize), serverChannel)
    (clientPair, serverPair)
  }
  

}