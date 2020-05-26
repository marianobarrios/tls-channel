package tlschannel.async

import java.nio.ByteBuffer
import java.util.concurrent.{CancellationException, TimeUnit}

import org.junit.runner.RunWith
import org.scalatest.Assertions
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner
import tlschannel.helpers.{SocketPairFactory, SslContextFactory}

@RunWith(classOf[JUnitRunner])
class AsyncCloseTest extends AnyFunSuite with AsyncTestBase with Assertions {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)

  val bufferSize = 10000

  test("should throw an AsynchronousCloseException when closing the group while reading") {
    val channelGroup = new AsynchronousTlsChannelGroup()
    val socketPair = factory.async(null, channelGroup, runTasks = true)

    val readBuffer = ByteBuffer.allocate(bufferSize)
    val readFuture = socketPair.server.external.read(readBuffer)
    socketPair.server.external.close()
    socketPair.client.external.close()

    assertThrows[CancellationException] {
      readFuture.get(1000, TimeUnit.MILLISECONDS)
    }

    shutdownChannelGroup(channelGroup)
    assertChannelGroupConsistency(channelGroup)

    assert(channelGroup.getCancelledReadCount == 1)
    assert(channelGroup.getFailedReadCount == 0)
    assert(channelGroup.getSuccessfulReadCount == 0)

    printChannelGroupStatus(channelGroup)
  }

  test("should throw an AsynchronousCloseException when closing the group while writing") {
    val channelGroup = new AsynchronousTlsChannelGroup()
    val socketPair = factory.async(null, channelGroup, runTasks = true)

    val writeBuffer = ByteBuffer.allocate(bufferSize)
    val writeFuture = socketPair.server.external.write(writeBuffer)
    socketPair.server.external.close()
    socketPair.client.external.close()

    assertThrows[CancellationException] {
      writeFuture.get(1000, TimeUnit.MILLISECONDS)
    }

    shutdownChannelGroup(channelGroup)
    assertChannelGroupConsistency(channelGroup)

    assert(channelGroup.getCancelledWriteCount == 1)
    assert(channelGroup.getFailedWriteCount == 0)
    assert(channelGroup.getSuccessfulWriteCount == 0)

    printChannelGroupStatus(channelGroup)
  }

}
