package tlschannel

import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.api.{AfterAll, Assertions, Test, TestInstance}
import tlschannel.helpers.NonBlockingLoops
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SslContextFactory

/** Test using concurrent, non-blocking connections, and a "null" [[javax.net.ssl.SSLEngine]] that just passes all byte
  * as they are.
  */
@TestInstance(Lifecycle.PER_CLASS)
class NullMultiNonBlockingTest {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)
  val dataSize = 10 * 1024 * 1024
  val totalConnections = 150

  @Test
  def testRunTasksInNonBlockingLoop(): Unit = {
    val pairs = factory.nioNioN(cipher = null, totalConnections, None, None)
    val report = NonBlockingLoops.loop(pairs, dataSize, renegotiate = false)
    Assertions.assertEquals(0, report.asyncTasksRun)
  }

  @AfterAll
  def afterAll() = {
    println(factory.getGlobalAllocationReport())
  }

}
