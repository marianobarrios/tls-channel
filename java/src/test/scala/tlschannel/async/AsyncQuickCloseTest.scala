package tlschannel.async

import org.scalatest.Assertions
import org.scalatest.funsuite.AnyFunSuite
import tlschannel.helpers.{SocketPairFactory, SslContextFactory}

import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import scala.concurrent.ExecutionException

class AsyncQuickCloseTest extends AnyFunSuite with AsyncTestBase with Assertions {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)

  /*
   * Closing sockets registered in an asynchronous channel group is inherently racy, using repetitions to try to catch
   * most races.
   */
  val repetitions = 500

  val bufferSize = 10000

  // see https://github.com/marianobarrios/tls-channel/issues/34
  test("immediate closings after registration") {
    val channelGroup = new AsynchronousTlsChannelGroup()
    for (_ <- 1 to repetitions) {

      // create (and register) channels and close immediately
      val socketPair = factory.async(null, channelGroup, runTasks = true)
      socketPair.server.external.close()
      socketPair.client.external.close()

      // try read
      val readBuffer = ByteBuffer.allocate(bufferSize)
      val readFuture = socketPair.server.external.read(readBuffer)
      val readEx = intercept[ExecutionException] {
        readFuture.get()
      }
      assert(readEx.getCause.isInstanceOf[ClosedChannelException])

      // try write
      val writeFuture = socketPair.client.external.write(ByteBuffer.wrap(Array(1)))
      val writeEx = intercept[ExecutionException] {
        writeFuture.get()
      }
      assert(writeEx.getCause.isInstanceOf[ClosedChannelException])

    }
    assert(channelGroup.isAlive)
    channelGroup.shutdown()
    assertChannelGroupConsistency(channelGroup)
  }

  test("immediate closings after registration, even if we close the raw channel") {
    val channelGroup = new AsynchronousTlsChannelGroup()
    for (_ <- 1 to repetitions) {

      // create (and register) channels and close immediately
      val socketPair = factory.async(null, channelGroup, runTasks = true)
      socketPair.server.plain.close()
      socketPair.client.plain.close()

      // try read
      val readBuffer = ByteBuffer.allocate(bufferSize)
      val readFuture = socketPair.server.external.read(readBuffer)
      val readEx = intercept[ExecutionException] {
        readFuture.get()
      }
      assert(readEx.getCause.isInstanceOf[ClosedChannelException])

      // try write
      val writeFuture = socketPair.client.external.write(ByteBuffer.wrap(Array(1)))
      val writeEx = intercept[ExecutionException] {
        writeFuture.get()
      }
      assert(writeEx.getCause.isInstanceOf[ClosedChannelException])

    }
    assert(channelGroup.isAlive)
    channelGroup.shutdown()
    assertChannelGroupConsistency(channelGroup)
  }

}
