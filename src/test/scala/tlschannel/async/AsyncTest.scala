package tlschannel.async

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle
import tlschannel.helpers.AsyncLoops
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SslContextFactory

@TestInstance(Lifecycle.PER_CLASS)
class AsyncTest extends AsyncTestBase {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)
  val socketPairCount = 50

  // real engine - run tasks
  @Test
  def testRunTasks(): Unit = {
    println("testRunTasks():")
    val channelGroup = new AsynchronousTlsChannelGroup()
    val dataSize = 5 * 1024 * 1024
    println(s"data size: $dataSize")
    val socketPairs = factory.asyncN(None, channelGroup, socketPairCount, runTasks = true)
    val report = AsyncLoops.loop(socketPairs, dataSize)

    shutdownChannelGroup(channelGroup)
    assertChannelGroupConsistency(channelGroup)
    assertEquals(0, channelGroup.getFailedReadCount)
    assertEquals(0, channelGroup.getFailedWriteCount)

    report.print()
    printChannelGroupStatus(channelGroup)
  }

  // real engine - do not run tasks
  @Test
  def testNotRunTasks(): Unit = {
    println("testNotRunTasks():")
    val channelGroup = new AsynchronousTlsChannelGroup()
    val dataSize = 2 * 1024 * 1024
    println(s"data size: $dataSize")
    val socketPairs = factory.asyncN(None, channelGroup, socketPairCount, runTasks = false)
    val report = AsyncLoops.loop(socketPairs, dataSize)

    shutdownChannelGroup(channelGroup)
    assertChannelGroupConsistency(channelGroup)

    assertEquals(0, channelGroup.getFailedReadCount)
    assertEquals(0, channelGroup.getFailedWriteCount)
    assertEquals(0, channelGroup.getCancelledReadCount)
    assertEquals(0, channelGroup.getCancelledWriteCount)

    report.print()
    printChannelGroupStatus(channelGroup)
  }

  // null engine
  @Test
  def testNullEngine(): Unit = {
    println("testNullEngine():")
    val channelGroup = new AsynchronousTlsChannelGroup()
    val dataSize = 12 * 1024 * 1024
    println(s"data size: $dataSize")
    val socketPairs = factory.asyncN(cipher = null, channelGroup, socketPairCount, runTasks = true)
    val report = AsyncLoops.loop(socketPairs, dataSize)

    shutdownChannelGroup(channelGroup)
    assertChannelGroupConsistency(channelGroup)

    assertEquals(0, channelGroup.getFailedReadCount)
    assertEquals(0, channelGroup.getFailedWriteCount)
    assertEquals(0, channelGroup.getCancelledReadCount)
    assertEquals(0, channelGroup.getCancelledWriteCount)

    report.print()
    printChannelGroupStatus(channelGroup)
  }

}
