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
  
  def plain_Old_Old() = {
    val serverSocket = new ServerSocket(port)
    val client = new Socket(localhost, port)
    val server = serverSocket.accept()
    serverSocket.close()
    (client, server)
  }

  def plain_Nio_Nio() = {
    val serverSocket = ServerSocketChannel.open()
    serverSocket.bind(address)
    val client = SocketChannel.open(address)
    val server = serverSocket.accept()
    serverSocket.close()
    ((new ChunkingByteChannel(client), client), (new ChunkingByteChannel(server), server))
  }

  def plain_Old_Nio() = {
    val serverSocket = ServerSocketChannel.open()
    serverSocket.bind(address)
    val client = new Socket(localhost, port)
    val server = serverSocket.accept()
    serverSocket.close()
    (client, (new ChunkingByteChannel(server), server))
  }

  def plain_Nio_Old() = {
    val serverSocket = new ServerSocket(port)
    val client = SocketChannel.open(address)
    val server = serverSocket.accept()
    serverSocket.close()
    ((new ChunkingByteChannel(client), client), server)
  }

  def tls_Old_Old(cipher: String) = {
    val serverSocket = createSslServerSocket(cipher, port)
    val client = createSslSocket(cipher, localhost, port)
    val server = serverSocket.accept()
    serverSocket.close()
    (client, server)
  }

  def tls_Old_Nio(cipher: String) = {
    val serverSocket = ServerSocketChannel.open()
    serverSocket.bind(address)
    val client = createSslSocket(cipher, localhost, port)
    val rawServer = serverSocket.accept()
    serverSocket.close()
    val server = new TlsServerSocketChannel(
        new ChunkingByteChannel(rawServer), n => sslContext, e => e.setEnabledCipherSuites(Array(cipher)))
    (client, (server, rawServer))
  }

  def tls_Nio_Nio(cipher: String) = {
    val ((plainClient, rawClient), (plainServer, rawServer)) = plain_Nio_Nio()
    val client = new TlsClientSocketChannel(rawClient, createSslEngine(cipher, client = true))
    val server = new TlsServerSocketChannel(rawServer, n => sslContext, e => e.setEnabledCipherSuites(Array(cipher)))
    ((client, rawClient), (server, rawServer))
  }

  def tls_Nio_Old(cipher: String) = {
    val serverSocket = createSslServerSocket(cipher, port)
    val rawClient = SocketChannel.open(address)
    val server = serverSocket.accept()
    serverSocket.close()
    val client = new TlsClientSocketChannel(new ChunkingByteChannel(rawClient), createSslEngine(cipher, client = true))
    ((client, rawClient), server)
  }

}