package tlschannel.async

import org.scalatest.Assertions
import org.scalatest.funsuite.AnyFunSuite
import tlschannel.helpers.AsyncLoops
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SslContextFactory
import tlschannel.helpers.TestUtil

class AsyncTest extends AnyFunSuite with Assertions with AsyncTestBase {

  val sslContextFactory = new SslContextFactory
  val (cipher, sslContext) = sslContextFactory.standardCipher
  val factory = new SocketPairFactory(sslContext)
  val socketPairCount = 120

  test("real engine - run tasks") {
    val channelGroup = new AsynchronousTlsChannelGroup()
    val dataSize = 5 * 1024 * 1024
    info(s"data size: $dataSize")
    val socketPairs = factory.asyncN(cipher, channelGroup, socketPairCount, runTasks = true)
    val (report, elapsed) = TestUtil.time {
      AsyncLoops.loop(socketPairs, dataSize)
    }

    shutdownChannelGroup(channelGroup)
    assertChannelGroupConsistency(channelGroup)
    assert(channelGroup.getFailedReadCount == 0)
    assert(channelGroup.getFailedWriteCount == 0)

    info(f"elapsed:            ${elapsed.toMillis}%8d ms")
    printReport(report)
    printChannelGroupStatus(channelGroup)
  }

  test("real engine - do not run tasks") {
    val channelGroup = new AsynchronousTlsChannelGroup()
    val dataSize = 2 * 1024 * 1024
    info(s"data size: $dataSize")
    val socketPairs = factory.asyncN(cipher, channelGroup, socketPairCount, runTasks = false)
    val (report, elapsed) = TestUtil.time {
      AsyncLoops.loop(socketPairs, dataSize)
    }

    shutdownChannelGroup(channelGroup)
    assertChannelGroupConsistency(channelGroup)

    assert(channelGroup.getFailedReadCount == 0)
    assert(channelGroup.getFailedWriteCount == 0)
    assert(channelGroup.getCancelledReadCount == 0)
    assert(channelGroup.getCancelledWriteCount == 0)

    info(f"elapsed:            ${elapsed.toMillis}%8d ms")
    printReport(report)
    printChannelGroupStatus(channelGroup)
  }

  test("null engine") {
    val channelGroup = new AsynchronousTlsChannelGroup()
    val dataSize = 12 * 1024 * 1024
    info(s"data size: $dataSize")
    val socketPairs = factory.asyncN(cipher = null, channelGroup, socketPairCount, runTasks = true)
    val (report, elapsed) = TestUtil.time {
      AsyncLoops.loop(socketPairs, dataSize)
    }

    shutdownChannelGroup(channelGroup)
    assertChannelGroupConsistency(channelGroup)

    assert(channelGroup.getFailedReadCount == 0)
    assert(channelGroup.getFailedWriteCount == 0)
    assert(channelGroup.getCancelledReadCount == 0)
    assert(channelGroup.getCancelledWriteCount == 0)

    info(f"elapsed:            ${elapsed.toMillis}%8d ms")
    printReport(report)
    printChannelGroupStatus(channelGroup)
  }

}
