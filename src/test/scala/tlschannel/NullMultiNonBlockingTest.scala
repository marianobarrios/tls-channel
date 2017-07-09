package tlschannel

import org.scalatest.BeforeAndAfterAll
import org.scalatest.ConfigMap
import org.scalatest.FunSuite
import org.scalatest.Matchers
import tlschannel.helpers.NonBlockingLoops
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SslContextFactory
import tlschannel.helpers.TestUtil

/**
  * Test using concurrent, non-blocking connections, and a "null" [[javax.net.ssl.SSLEngine]] that just passes
  * all byte as they are.
  */
class NullMultiNonBlockingTest extends FunSuite with Matchers with NonBlockingSuite with BeforeAndAfterAll {

  val (cipher, sslContext) = SslContextFactory.standardCipher
  val factory = new SocketPairFactory(sslContext, SslContextFactory.certificateCommonName)
  val dataSize = 10 * 1024 * 1024
  val totalConnections = 150

  test("running tasks in non-blocking loop") {
    val pairs = factory.nullNioNioN(totalConnections, None, None)
    val (report, elapsed) = TestUtil.time {
      NonBlockingLoops.loop(pairs, dataSize, renegotiate = false)
    }
    assert(report.asyncTasksRun === 0)
    printReport(report, elapsed)
  }

  override def afterAll(configMap: ConfigMap) = {
    printGlobalAllocationReport(factory)
  }

}