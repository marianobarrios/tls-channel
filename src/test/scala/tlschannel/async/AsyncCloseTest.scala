package tlschannel.async

import java.nio.ByteBuffer
import java.util.concurrent.{CancellationException, TimeUnit}
import org.junit.runner.RunWith
import org.scalatest.Assertions
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner
import tlschannel.helpers.{SocketPairFactory, SslContextFactory}

import java.nio.channels.ClosedChannelException
import scala.concurrent.ExecutionException

@RunWith(classOf[JUnitRunner])
class AsyncCloseTest extends AnyFunSuite with AsyncTestBase with Assertions {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)

  val bufferSize = 10000

  /*
   * Closing sockets registered in an asynchronous channel group is inherently racy, using repetitions to try to catch
   * most races.
   */
  val repetitions = 2000

  test("should throw an CancellationException (or ClosedChannelException) when closing the group while reading") {
    for (_ <- 1 to repetitions) {
      val channelGroup = new AsynchronousTlsChannelGroup()
      val socketPair = factory.async(null, channelGroup, runTasks = true)

      val readBuffer = ByteBuffer.allocate(bufferSize)
      val readFuture = socketPair.server.external.read(readBuffer)

      socketPair.server.external.close()
      socketPair.client.external.close()

      intercept[Exception] {
        readFuture.get(1000, TimeUnit.MILLISECONDS)
      } match {
        case _: CancellationException =>
          assert(channelGroup.getCancelledReadCount == 1)
          assert(channelGroup.getFailedReadCount == 0)
        case ee: ExecutionException =>
          assert(ee.getCause.isInstanceOf[ClosedChannelException])
          assert(channelGroup.getCancelledReadCount == 0)
          assert(channelGroup.getFailedReadCount == 1)
        case e => fail(e)
      }

      shutdownChannelGroup(channelGroup)
      assertChannelGroupConsistency(channelGroup)

      assert(channelGroup.getSuccessfulReadCount == 0)

      printChannelGroupStatus(channelGroup)
    }
  }

  test("should throw an CancellationException (or ClosedChannelException) when closing the group while writing") {
    for (_ <- 1 to repetitions) {
      val channelGroup = new AsynchronousTlsChannelGroup()
      val socketPair = factory.async(null, channelGroup, runTasks = true)

      val writeBuffer = ByteBuffer.allocate(bufferSize)
      val writeFuture = socketPair.server.external.write(writeBuffer)
      socketPair.server.external.close()
      socketPair.client.external.close()

      intercept[Exception] {
        writeFuture.get(1000, TimeUnit.MILLISECONDS)
      } match {
        case _: CancellationException =>
          assert(channelGroup.getCancelledWriteCount == 1)
          assert(channelGroup.getFailedWriteCount == 0)
        case ee: ExecutionException =>
          assert(ee.getCause.isInstanceOf[ClosedChannelException])
          assert(channelGroup.getCancelledWriteCount == 0)
          assert(channelGroup.getFailedWriteCount == 1)
        case e => fail(e)
      }

      shutdownChannelGroup(channelGroup)
      assertChannelGroupConsistency(channelGroup)

      assert(channelGroup.getSuccessfulWriteCount == 0)

      printChannelGroupStatus(channelGroup)
    }
  }

}
