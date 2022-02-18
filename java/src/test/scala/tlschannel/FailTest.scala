package tlschannel

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{ServerSocketChannel, SocketChannel}

import com.typesafe.scalalogging.StrictLogging
import javax.net.ssl.SSLException
import org.scalatest.Assertions
import org.scalatest.funsuite.AnyFunSuite
import tlschannel.helpers.{SocketPairFactory, SslContextFactory, TestUtil}

class FailTest extends AnyFunSuite with Assertions with StrictLogging {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)

  test("Plain -> TLS connection (fail immediately)") {

    val serverSocket = ServerSocketChannel.open()
    serverSocket.bind(new InetSocketAddress(factory.localhost, 0 /* find free port */ ))
    val chosenPort = serverSocket.getLocalAddress.asInstanceOf[InetSocketAddress].getPort
    val address = new InetSocketAddress(factory.localhost, chosenPort)
    val clientChannel = SocketChannel.open(address)
    val rawServer = serverSocket.accept()
    factory.createClientSslEngine(None, chosenPort)
    val serverChannelBuilder = ServerTlsChannel
      .newBuilder(
        rawServer,
        nameOpt => factory.sslContextFactory(factory.clientSniHostName, factory.sslContext)(nameOpt)
      )
      .withEngineFactory(factory.fixedCipherServerSslEngineFactory(None) _)

    val serverChannel = serverChannelBuilder.build()

    def serverFn(): Unit = TestUtil.cannotFail {
      val buffer = ByteBuffer.allocate(10000)
      assertThrows[SSLException] {
        serverChannel.read(buffer)
      }
      serverChannel.close()
    }
    val serverThread = new Thread(() => serverFn(), "server-thread")
    serverThread.start()

    val message = "12345\n"
    clientChannel.write(ByteBuffer.wrap(message.getBytes))
    val buffer = ByteBuffer.allocate(1)
    assert(clientChannel.read(buffer) == -1)
    clientChannel.close()

    serverThread.join()
  }

}
