package tlschannel

import org.scalatest.{Assertions, BeforeAndAfterAll}
import org.scalatest.funsuite.AnyFunSuite
import tlschannel.helpers.NonBlockingLoops
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SslContextFactory
import tlschannel.helpers.TestUtil

/**
  * Test using concurrent, non-blocking connections, and a "null" [[javax.net.ssl.SSLEngine]] that just passes
  * all byte as they are.
  */
class NullMultiNonBlockingTest extends AnyFunSuite with Assertions with NonBlockingSuite with BeforeAndAfterAll {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)
  val dataSize = 10 * 1024 * 1024
  val totalConnections = 150

  test("running tasks in non-blocking loop") {
    val pairs = factory.nioNioN(cipher = null, totalConnections, None, None)
    val (report, elapsed) = TestUtil.time {
      NonBlockingLoops.loop(pairs, dataSize, renegotiate = false)
    }
    assert(report.asyncTasksRun == 0)
    printReport(report, elapsed)
  }

  override def afterAll() = {
    info(factory.getGlobalAllocationReport())
  }

}
