package tlschannel.async

import java.nio.ByteBuffer
import java.util.concurrent.{CancellationException, TimeUnit}
import org.scalatest.Assertions
import org.scalatest.funsuite.AnyFunSuite
import tlschannel.helpers.{SocketPairFactory, SslContextFactory}

import java.nio.channels.ClosedChannelException
import scala.concurrent.ExecutionException

class AsyncCloseTest extends AnyFunSuite with AsyncTestBase with Assertions {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)

  val bufferSize = 10000

  /*
   * Closing sockets registered in an asynchronous channel group is inherently racy, using repetitions to try to catch
   * most races.
   */
  val repetitions = 500

  test("should throw an CancellationException (or ClosedChannelException) when closing the group while reading") {
    for (_ <- 1 to repetitions) {
      val channelGroup = new AsynchronousTlsChannelGroup()
      val socketPair = factory.async(null, channelGroup, runTasks = true)

      val readBuffer = ByteBuffer.allocate(bufferSize)
      val readFuture = socketPair.server.external.read(readBuffer)

      socketPair.server.external.close()

      intercept[Exception] {
        readFuture.get(1000, TimeUnit.MILLISECONDS)
      } match {
        case _: CancellationException =>
          // give time to adders to converge
          Thread.sleep(10)
          assert(channelGroup.getCancelledReadCount == 1)
          assert(channelGroup.getFailedReadCount == 0)
        case ee: ExecutionException =>
          // give time to adders to converge
          Thread.sleep(10)
          assert(ee.getCause.isInstanceOf[ClosedChannelException])
          assert(channelGroup.getCancelledReadCount == 0)
          assert(channelGroup.getFailedReadCount == 1)
        case e => fail(e)
      }

      socketPair.client.external.close()
      shutdownChannelGroup(channelGroup)
      assertChannelGroupConsistency(channelGroup)
      assert(channelGroup.getSuccessfulReadCount == 0)
      channelGroup.shutdown()
    }
  }

  test(
    "should throw an CancellationException (or ClosedChannelException) when closing the group while reading, even if we close the raw channel"
  ) {
    for (_ <- 1 to repetitions) {
      val channelGroup = new AsynchronousTlsChannelGroup()
      val socketPair = factory.async(null, channelGroup, runTasks = true)

      val readBuffer = ByteBuffer.allocate(bufferSize)
      val readFuture = socketPair.server.external.read(readBuffer)

      // important: closing the raw socket
      socketPair.server.plain.close()

      intercept[Exception] {
        readFuture.get(1000, TimeUnit.MILLISECONDS)
      } match {
        case _: CancellationException =>
          // give time to adders to converge
          Thread.sleep(10)
          assert(channelGroup.getCancelledReadCount == 1)
          assert(channelGroup.getFailedReadCount == 0)
        case ee: ExecutionException =>
          // give time to adders to converge
          Thread.sleep(10)
          assert(ee.getCause.isInstanceOf[ClosedChannelException])
          assert(channelGroup.getCancelledReadCount == 0)
          assert(channelGroup.getFailedReadCount == 1)
        case e => fail(e)
      }

      socketPair.client.external.close()
      shutdownChannelGroup(channelGroup)
      assertChannelGroupConsistency(channelGroup)
      assert(channelGroup.getSuccessfulReadCount == 0)
      channelGroup.shutdown()
    }
  }
}
