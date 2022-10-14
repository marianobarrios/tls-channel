package tlschannel

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.{ServerSocketChannel, SocketChannel}
import com.typesafe.scalalogging.StrictLogging
import org.junit.jupiter.api.Assertions.{assertEquals, assertThrows}
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle

import javax.net.ssl.SSLException
import tlschannel.helpers.{SocketPairFactory, SslContextFactory, TestUtil}

@TestInstance(Lifecycle.PER_CLASS)
class FailTest extends StrictLogging {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)

  @Test
  def testPlanToTls(): Unit = {
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

      assertThrows(classOf[SSLException], () => serverChannel.read(buffer))
      serverChannel.close()
    }
    val serverThread = new Thread(() => serverFn(), "server-thread")
    serverThread.start()

    val message = "12345\n"
    clientChannel.write(ByteBuffer.wrap(message.getBytes))
    val buffer = ByteBuffer.allocate(1)
    assertEquals(-1, clientChannel.read(buffer))
    clientChannel.close()

    serverThread.join()
  }

}
