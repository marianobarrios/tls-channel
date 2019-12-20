package tlschannel.async

import java.util.concurrent.TimeUnit

import org.scalatest.{Assertions, Informing}
import tlschannel.helpers.AsyncLoops

trait AsyncTestBase extends Informing with Assertions {

  def printReport(report: AsyncLoops.Report) = {
    info(f"test loop:")
    info(f"  dequeue cycles:   ${report.dequeueCycles}%8d")
    info(f"  completed reads:  ${report.completedReads}%8d")
    info(f"  failed reads:     ${report.failedReads}%8d")
    info(f"  completed writes: ${report.completedWrites}%8d")
    info(f"  failed writes:    ${report.failedWrites}%8d")
  }

  def printChannelGroupStatus(channelGroup: AsynchronousTlsChannelGroup) = {
    info(f"channel group:")
    info(f"  selection cycles: ${channelGroup.getSelectionCount}%8d")
    info(f"  started reads:    ${channelGroup.getStartedReadCount}%8d")
    info(f"  successful reads: ${channelGroup.getSuccessfulReadCount}%8d")
    info(f"  failed reads:     ${channelGroup.getFailedReadCount}%8d")
    info(f"  cancelled reads:  ${channelGroup.getCancelledReadCount}%8d")
    info(f"  started writes:   ${channelGroup.getStartedWriteCount}%8d")
    info(f"  successful write: ${channelGroup.getSuccessfulWriteCount}%8d")
    info(f"  failed writes:    ${channelGroup.getFailedWriteCount}%8d")
    info(f"  cancelled writes: ${channelGroup.getCancelledWriteCount}%8d")
  }

  def shutdownChannelGroup(group: AsynchronousTlsChannelGroup) = {
    group.shutdown()
    val terminated = group.awaitTermination(100, TimeUnit.MILLISECONDS)
    assert(terminated)
  }

  def assertChannelGroupConsistency(group: AsynchronousTlsChannelGroup) = {
    assert(group.getCurrentRegistrationCount == 0)
    assert(group.getCurrentReadCount == 0)
    assert(group.getCurrentWriteCount == 0)
    assert(
      group.getStartedReadCount == group.getCancelledReadCount + group.getSuccessfulReadCount + group.getFailedReadCount
    )
    assert(
      group.getStartedWriteCount == group.getCancelledWriteCount + group.getSuccessfulWriteCount + group.getFailedWriteCount
    )
  }
}
