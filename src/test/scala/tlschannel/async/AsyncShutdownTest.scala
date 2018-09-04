package tlschannel.async

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

import org.scalatest.FunSuite
import tlschannel.helpers.AsyncSocketPair
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SslContextFactory

class AsyncShutdownTest extends FunSuite with AsyncTestBase {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.anonContext)

  val bufferSize = 10

  test("immediate shutdown") {
    val channelGroup = new AsynchronousTlsChannelGroup()
    val socketPairCount = 100
    val socketPairs = factory.asyncN(null, channelGroup, socketPairCount, runTasks = true)
    for (AsyncSocketPair(client, server) <- socketPairs) {
      val writeBuffer = ByteBuffer.allocate(bufferSize)
      client.external.write(writeBuffer)
      val readBuffer = ByteBuffer.allocate(bufferSize)
      server.external.read(readBuffer)
    }

    assert(!channelGroup.isTerminated)

    channelGroup.shutdownNow()

    // terminated even after a relatively short timeout
    val terminated = channelGroup.awaitTermination(100, TimeUnit.MILLISECONDS)
    assert(terminated)
    assert(channelGroup.isTerminated)

    // give time to adders to converge
    Thread.sleep(500)

    assertChannelGroupConsistency(channelGroup)

    assert(channelGroup.getFailedReadCount === 0)
    assert(channelGroup.getFailedWriteCount === 0)

    printChannelGroupStatus(channelGroup)
  }

  test("non-immediate shutdown") {
    val channelGroup = new AsynchronousTlsChannelGroup()
    val socketPairCount = 100
    val socketPairs = factory.asyncN(null, channelGroup, socketPairCount, runTasks = true)
    for (AsyncSocketPair(client, server) <- socketPairs) {
      val writeBuffer = ByteBuffer.allocate(bufferSize)
      client.external.write(writeBuffer)
      val readBuffer = ByteBuffer.allocate(bufferSize)
      server.external.read(readBuffer)
    }

    assert(!channelGroup.isTerminated)

    channelGroup.shutdown()

    {
      // not terminated even after a relatively long timeout
      val terminated = channelGroup.awaitTermination(2000, TimeUnit.MILLISECONDS)
      assert(!terminated)
      assert(!channelGroup.isTerminated)
    }

    for (AsyncSocketPair(client, server) <- socketPairs) {
      client.external.close()
      server.external.close()
    }

    {
      // terminated even after a relatively short timeout
      val terminated = channelGroup.awaitTermination(100, TimeUnit.MILLISECONDS)
      assert(terminated)
      assert(channelGroup.isTerminated)
    }

    assertChannelGroupConsistency(channelGroup)

    assert(channelGroup.getCancelledReadCount === 0)
    assert(channelGroup.getCancelledWriteCount === 0)
    assert(channelGroup.getFailedReadCount === 0)
    assert(channelGroup.getFailedWriteCount === 0)

    printChannelGroupStatus(channelGroup)
  }

}
