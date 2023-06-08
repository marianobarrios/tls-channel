package tlschannel.async

import org.junit.jupiter.api.Assertions.{assertEquals, assertFalse, assertTrue}

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import tlschannel.helpers.AsyncSocketPair
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SslContextFactory
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle

@TestInstance(Lifecycle.PER_CLASS)
class AsyncShutdownTest extends AsyncTestBase {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)

  val bufferSize = 10

  @Test
  def testImmediateShutdown(): Unit = {
    println("testImmediateShutdown():")
    val channelGroup = new AsynchronousTlsChannelGroup()
    val socketPairCount = 50
    val socketPairs = factory.asyncN(null, channelGroup, socketPairCount, runTasks = true)
    for (AsyncSocketPair(client, server) <- socketPairs) {
      val writeBuffer = ByteBuffer.allocate(bufferSize)
      client.external.write(writeBuffer)
      val readBuffer = ByteBuffer.allocate(bufferSize)
      server.external.read(readBuffer)
    }

    assertFalse(channelGroup.isTerminated)

    channelGroup.shutdownNow()

    // terminated even after a relatively short timeout
    val terminated = channelGroup.awaitTermination(100, TimeUnit.MILLISECONDS)
    assertTrue(terminated)
    assertTrue(channelGroup.isTerminated)
    assertChannelGroupConsistency(channelGroup)

    printChannelGroupStatus(channelGroup)
  }

  @Test
  def testNonImmediateShutdown(): Unit = {
    println("testNonImmediateShutdown():")
    val channelGroup = new AsynchronousTlsChannelGroup()
    val socketPairCount = 50
    val socketPairs = factory.asyncN(null, channelGroup, socketPairCount, runTasks = true)
    for (AsyncSocketPair(client, server) <- socketPairs) {
      val writeBuffer = ByteBuffer.allocate(bufferSize)
      client.external.write(writeBuffer)
      val readBuffer = ByteBuffer.allocate(bufferSize)
      server.external.read(readBuffer)
    }

    assertFalse(channelGroup.isTerminated)

    channelGroup.shutdown()

    {
      // not terminated even after a relatively long timeout
      val terminated = channelGroup.awaitTermination(2000, TimeUnit.MILLISECONDS)
      assertFalse(terminated)
      assertFalse(channelGroup.isTerminated)
    }

    for (AsyncSocketPair(client, server) <- socketPairs) {
      client.external.close()
      server.external.close()
    }

    {
      // terminated even after a relatively short timeout
      val terminated = channelGroup.awaitTermination(100, TimeUnit.MILLISECONDS)
      assertTrue(terminated)
      assertTrue(channelGroup.isTerminated)
    }

    assertChannelGroupConsistency(channelGroup)

    assertEquals(0, channelGroup.getCancelledReadCount)
    assertEquals(0, channelGroup.getCancelledWriteCount)
    assertEquals(0, channelGroup.getFailedReadCount)
    assertEquals(0, channelGroup.getFailedWriteCount)

    printChannelGroupStatus(channelGroup)
  }

}
