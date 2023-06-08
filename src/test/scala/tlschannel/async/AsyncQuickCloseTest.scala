package tlschannel.async

import org.junit.jupiter.api.Assertions.{assertInstanceOf, assertTrue}
import org.junit.jupiter.api.TestInstance.Lifecycle
import tlschannel.helpers.{SocketPairFactory, SslContextFactory}

import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import scala.concurrent.ExecutionException
import org.junit.jupiter.api.{Assertions, Test, TestInstance}

@TestInstance(Lifecycle.PER_CLASS)
class AsyncQuickCloseTest extends AsyncTestBase {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)

  /*
   * Closing sockets registered in an asynchronous channel group is inherently racy, using repetitions to try to catch
   * most races.
   */
  val repetitions = 250

  val bufferSize = 10000

  // see https://github.com/marianobarrios/tls-channel/issues/34
  // immediate closings after registration
  @Test
  def testImmediateClose(): Unit = {
    val channelGroup = new AsynchronousTlsChannelGroup()
    for (_ <- 1 to repetitions) {

      // create (and register) channels and close immediately
      val socketPair = factory.async(null, channelGroup, runTasks = true)
      socketPair.server.external.close()
      socketPair.client.external.close()

      // try read
      val readBuffer = ByteBuffer.allocate(bufferSize)
      val readFuture = socketPair.server.external.read(readBuffer)
      val e1 = Assertions.assertThrows(classOf[ExecutionException], () => readFuture.get())
      assertInstanceOf(classOf[ClosedChannelException], e1.getCause)

      // try write
      val writeFuture = socketPair.client.external.write(ByteBuffer.wrap(Array(1)))
      val e2 = Assertions.assertThrows(classOf[ExecutionException], () => writeFuture.get())
      assertInstanceOf(classOf[ClosedChannelException], e2.getCause)
    }

    assertTrue(channelGroup.isAlive)
    channelGroup.shutdown()
    assertChannelGroupConsistency(channelGroup)
  }

  // immediate closings after registration, even if we close the raw channel
  @Test
  def testRawImmediateClosing(): Unit = {
    val channelGroup = new AsynchronousTlsChannelGroup()
    for (_ <- 1 to repetitions) {

      // create (and register) channels and close immediately
      val socketPair = factory.async(null, channelGroup, runTasks = true)
      socketPair.server.plain.close()
      socketPair.client.plain.close()

      // try read
      val readBuffer = ByteBuffer.allocate(bufferSize)
      val readFuture = socketPair.server.external.read(readBuffer)
      val readEx = Assertions.assertThrows(classOf[ExecutionException], () => readFuture.get())
      assertInstanceOf(classOf[ClosedChannelException], readEx.getCause)

      // try write
      val writeFuture = socketPair.client.external.write(ByteBuffer.wrap(Array(1)))
      val writeEx = Assertions.assertThrows(classOf[ExecutionException], () => writeFuture.get())
      assertInstanceOf(classOf[ClosedChannelException], writeEx.getCause)
    }
    assertTrue(channelGroup.isAlive)
    channelGroup.shutdown()
    assertChannelGroupConsistency(channelGroup)
  }

}
