package tlschannel.async

import org.junit.jupiter.api.Assertions.{assertEquals, assertInstanceOf}
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.api.{Assertions, Test, TestInstance}

import java.nio.ByteBuffer
import java.util.concurrent.{CancellationException, TimeUnit}
import tlschannel.helpers.{SocketPairFactory, SslContextFactory}

import java.nio.channels.ClosedChannelException
import scala.concurrent.ExecutionException

@TestInstance(Lifecycle.PER_CLASS)
class AsyncCloseTest extends AsyncTestBase {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)

  val bufferSize = 10000

  /*
   * Closing sockets registered in an asynchronous channel group is inherently racy, using repetitions to try to catch
   * most races.
   */
  val repetitions = 250

  // should throw an CancellationException (or ClosedChannelException) when closing the group while reading
  @Test
  def testClosingWhileReading(): Unit = {
    for (_ <- 1 to repetitions) {
      val channelGroup = new AsynchronousTlsChannelGroup()
      val socketPair = factory.async(null, channelGroup, runTasks = true)

      val readBuffer = ByteBuffer.allocate(bufferSize)
      val readFuture = socketPair.server.external.read(readBuffer)

      socketPair.server.external.close()

      try {
        readFuture.get(1000, TimeUnit.MILLISECONDS)
      } catch {
        case _: CancellationException =>
          // give time to adders to converge
          Thread.sleep(10)
          assertEquals(1, channelGroup.getCancelledReadCount)
          assertEquals(0, channelGroup.getFailedReadCount)
        case ee: ExecutionException =>
          // give time to adders to converge
          Thread.sleep(10)
          assertInstanceOf(classOf[ClosedChannelException], ee.getCause)
          assertEquals(0, channelGroup.getCancelledReadCount)
          assertEquals(1, channelGroup.getFailedReadCount)
        case e =>
          Assertions.fail(e)
      }

      socketPair.client.external.close()
      shutdownChannelGroup(channelGroup)
      assertChannelGroupConsistency(channelGroup)
      assertEquals(0, channelGroup.getSuccessfulReadCount)
      channelGroup.shutdown()
    }
  }

  // should throw an CancellationException (or ClosedChannelException) when closing the group while reading, even if we close the raw channel
  @Test
  def testRawClosingWhileReading(): Unit = {
    for (_ <- 1 to repetitions) {
      val channelGroup = new AsynchronousTlsChannelGroup()
      val socketPair = factory.async(null, channelGroup, runTasks = true)

      val readBuffer = ByteBuffer.allocate(bufferSize)
      val readFuture = socketPair.server.external.read(readBuffer)

      // important: closing the raw socket
      socketPair.server.plain.close()

      try {
        readFuture.get(1000, TimeUnit.MILLISECONDS)
      } catch {
        case _: CancellationException =>
          // give time to adders to converge
          Thread.sleep(10)
          assertEquals(1, channelGroup.getCancelledReadCount)
          assertEquals(0, channelGroup.getFailedReadCount)
        case ee: ExecutionException =>
          // give time to adders to converge
          Thread.sleep(10)
          assertInstanceOf(classOf[ClosedChannelException], ee.getCause)
          assertEquals(0, channelGroup.getCancelledReadCount)
          assertEquals(1, channelGroup.getFailedReadCount)
        case e =>
          Assertions.fail(e)
      }

      socketPair.client.external.close()
      shutdownChannelGroup(channelGroup)
      assertChannelGroupConsistency(channelGroup)
      assertEquals(0, channelGroup.getSuccessfulReadCount)
      channelGroup.shutdown()
    }
  }

}
