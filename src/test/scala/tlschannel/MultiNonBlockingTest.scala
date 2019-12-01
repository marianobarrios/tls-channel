package tlschannel

import org.scalatest.{Assertions, BeforeAndAfterAll}
import com.typesafe.scalalogging.StrictLogging
import org.scalatest.funsuite.AnyFunSuite
import tlschannel.helpers.NonBlockingLoops
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SslContextFactory
import tlschannel.helpers.TestUtil

class MultiNonBlockingTest extends AnyFunSuite with Assertions with StrictLogging with NonBlockingSuite with BeforeAndAfterAll {

  val sslContextFactory = new SslContextFactory
  val (cipher, sslContext) = sslContextFactory.standardCipher
  val factory = new SocketPairFactory(sslContext)
  val dataSize = 100 * 1024
  val totalConnections = 200

  test("running tasks in non-blocking loop - no renegotiation") {
    val pairs = factory.nioNioN(cipher, totalConnections, None, None, None, None, runTasks = true)
    val (report, elapsed) = TestUtil.time {
      NonBlockingLoops.loop(pairs, dataSize, renegotiate = false)
    }
    assert(report.asyncTasksRun == 0)
    printReport(report, elapsed)
  }

  test("running tasks in executor - no renegotiation") {
    val pairs = factory.nioNioN(cipher, totalConnections, None, None, None, None, runTasks = false)
    val (report, elapsed) = TestUtil.time {
      NonBlockingLoops.loop(pairs, dataSize, renegotiate = false)
    }
    printReport(report, elapsed)
  }

  test("running tasks in non-blocking loop - with renegotiation") {
    val pairs = factory.nioNioN(cipher, totalConnections, None, None, None, None, runTasks = true)
    val (report, elapsed) = TestUtil.time {
      NonBlockingLoops.loop(pairs, dataSize, renegotiate = true)
    }
    assert(report.asyncTasksRun == 0)
    printReport(report, elapsed)
  }

  test("running tasks in executor - with renegotiation") {
    val pairs = factory.nioNioN(cipher, totalConnections, None, None, None, None, runTasks = false)
    val (report, elapsed) = TestUtil.time {
      NonBlockingLoops.loop(pairs, dataSize, renegotiate = true)
    }
    printReport(report, elapsed)
  }

  override def afterAll() = {
    factory.printGlobalAllocationReport()
  }

}