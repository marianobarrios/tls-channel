package tlschannel.async

import org.junit.jupiter.api.Assertions.{assertEquals, assertTrue}

import java.util.concurrent.TimeUnit

trait AsyncTestBase {

  def printChannelGroupStatus(channelGroup: AsynchronousTlsChannelGroup): Unit = {
    println(f"channel group:")
    println(f"  selection cycles: ${channelGroup.getSelectionCount}%8d")
    println(f"  started reads:    ${channelGroup.getStartedReadCount}%8d")
    println(f"  successful reads: ${channelGroup.getSuccessfulReadCount}%8d")
    println(f"  failed reads:     ${channelGroup.getFailedReadCount}%8d")
    println(f"  cancelled reads:  ${channelGroup.getCancelledReadCount}%8d")
    println(f"  started writes:   ${channelGroup.getStartedWriteCount}%8d")
    println(f"  successful write: ${channelGroup.getSuccessfulWriteCount}%8d")
    println(f"  failed writes:    ${channelGroup.getFailedWriteCount}%8d")
    println(f"  cancelled writes: ${channelGroup.getCancelledWriteCount}%8d")
  }

  def shutdownChannelGroup(group: AsynchronousTlsChannelGroup): Unit = {
    group.shutdown()
    val terminated = group.awaitTermination(100, TimeUnit.MILLISECONDS)
    assertTrue(terminated)
  }

  def assertChannelGroupConsistency(group: AsynchronousTlsChannelGroup): Unit = {
    // give time to adders to converge
    Thread.sleep(10)
    assertEquals(0, group.getCurrentRegistrationCount)
    assertEquals(0, group.getCurrentReadCount)
    assertEquals(0, group.getCurrentWriteCount)
    assertEquals(
      group.getCancelledReadCount + group.getSuccessfulReadCount + group.getFailedReadCount,
      group.getStartedReadCount
    )
    assertEquals(
      group.getCancelledWriteCount + group.getSuccessfulWriteCount + group.getFailedWriteCount,
      group.getStartedWriteCount
    )
  }
}
