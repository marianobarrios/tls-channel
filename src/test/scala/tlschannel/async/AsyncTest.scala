package tlschannel.async

import java.util.concurrent.Executors

import org.scalatest.FunSuite
import tlschannel.helpers.AsyncLoops
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SslContextFactory
import tlschannel.helpers.TestUtil

class AsyncTest extends FunSuite with AsyncTestBase {

  val sslContextFactory = new SslContextFactory
  val (cipher, sslContext) = sslContextFactory.standardCipher
  val factory = new SocketPairFactory(sslContext)
  val handlerExecutor = Executors.newWorkStealingPool()
  val socketPairCount = 120

  test("real engine - run tasks") {
    val channelGroup = new AsynchronousTlsChannelGroup(Runtime.getRuntime.availableProcessors)
    val dataSize = 5 * 1024 * 1024
    info(s"data size: $dataSize")
    val socketPairs = factory.asyncN(cipher, handlerExecutor, channelGroup, socketPairCount, runTasks = true)
    val (report, elapsed) = TestUtil.time {
      AsyncLoops.loop(socketPairs, dataSize)
    }

    shutdownChannelGroup(channelGroup)
    assertChannelGroupConsistency(channelGroup)
    assert(channelGroup.getFailedReadCount === 0)
    assert(channelGroup.getFailedWriteCount === 0)

    info(f"elapsed:            ${elapsed.toMillis}%8d ms")
    printReport(report)
    printChannelGroupStatus(channelGroup)
  }

  test("real engine - do not run tasks") {
    val channelGroup = new AsynchronousTlsChannelGroup(Runtime.getRuntime.availableProcessors)
    val dataSize = 2 * 1024 * 1024
    info(s"data size: $dataSize")
    val socketPairs = factory.asyncN(cipher, handlerExecutor, channelGroup, socketPairCount, runTasks = false)
    val (report, elapsed) = TestUtil.time {
      AsyncLoops.loop(socketPairs, dataSize)
    }

    shutdownChannelGroup(channelGroup)
    assertChannelGroupConsistency(channelGroup)

    assert(channelGroup.getFailedReadCount === 0)
    assert(channelGroup.getFailedWriteCount === 0)
    assert(channelGroup.getCancelledReadCount === 0)
    assert(channelGroup.getCancelledWriteCount === 0)

    info(f"elapsed:            ${elapsed.toMillis}%8d ms")
    printReport(report)
    printChannelGroupStatus(channelGroup)
  }

  test("null engine") {
    val channelGroup = new AsynchronousTlsChannelGroup(Runtime.getRuntime.availableProcessors)
    val dataSize = 12 * 1024 * 1024
    info(s"data size: $dataSize")
    val socketPairs = factory.asyncN(cipher = null, handlerExecutor, channelGroup, socketPairCount, runTasks = true)
    val (report, elapsed) = TestUtil.time {
      AsyncLoops.loop(socketPairs, dataSize)
    }

    shutdownChannelGroup(channelGroup)
    assertChannelGroupConsistency(channelGroup)

    assert(channelGroup.getFailedReadCount === 0)
    assert(channelGroup.getFailedWriteCount === 0)
    assert(channelGroup.getCancelledReadCount === 0)
    assert(channelGroup.getCancelledWriteCount === 0)

    info(f"elapsed:            ${elapsed.toMillis}%8d ms")
    printReport(report)
    printChannelGroupStatus(channelGroup)
  }

}
