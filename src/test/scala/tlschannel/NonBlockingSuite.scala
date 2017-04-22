package tlschannel

import org.scalatest.FunSuite
import tlschannel.helpers.NonBlockingLoops

trait NonBlockingSuite extends FunSuite {

  def printReport(report: NonBlockingLoops.Report, elapsed: Long) = {
    info(s"Selector cycles: ${report.selectorCycles}")
    info(s"NeedRead count: ${report.needReadCount}")
    info(s"NeedWrite count: ${report.needWriteCount}")
    info(s"Renegociation count: ${report.renegotiationCount}")
    info(s"Asynchronous tasks run: ${report.asyncTasksRun}")
    info(s"Total asynchronous task running time: ${report.totalAsyncTaskRunningTimeMs} ms")
    info(f"Elapsed: ${elapsed / 1000}%d ms")
  }

}