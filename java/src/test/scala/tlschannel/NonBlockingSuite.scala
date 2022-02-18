package tlschannel

import java.time.Duration

import org.scalatest.funsuite.AnyFunSuite
import tlschannel.helpers.NonBlockingLoops

trait NonBlockingSuite extends AnyFunSuite {

  def printReport(report: NonBlockingLoops.Report, elapsed: Duration) = {
    info(s"Selector cycles: ${report.selectorCycles}")
    info(s"NeedRead count: ${report.needReadCount}")
    info(s"NeedWrite count: ${report.needWriteCount}")
    info(s"Renegotiation count: ${report.renegotiationCount}")
    info(s"Asynchronous tasks run: ${report.asyncTasksRun}")
    info(s"Total asynchronous task running time: ${report.totalAsyncTaskRunningTime.toMillis} ms")
    info(s"Elapsed: ${elapsed.toMillis} ms")
  }

}
