package tlschannel

import com.typesafe.scalalogging.StrictLogging
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.{AfterAll, Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle
import tlschannel.helpers.NonBlockingLoops
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SslContextFactory

@TestInstance(Lifecycle.PER_CLASS)
class MultiNonBlockingTest extends StrictLogging {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)
  val dataSize = 50 * 1024
  val totalConnections = 200

  // running tasks in non-blocking loop - no renegotiation
  @Test
  def testTaskLoop(): Unit = {
    println("testTasksInExecutorWithRenegotiation():")
    val pairs = factory.nioNioN(None, totalConnections, None, None, None, None, runTasks = true)
    val report = NonBlockingLoops.loop(pairs, dataSize, renegotiate = false)
    assertEquals(0, report.asyncTasksRun)
    report.print()
  }

  // running tasks in executor - no renegotiation
  @Test
  def testTasksInExecutor(): Unit = {
    println("testTasksInExecutorWithRenegotiation():")
    val pairs = factory.nioNioN(None, totalConnections, None, None, None, None, runTasks = false)
    val report = NonBlockingLoops.loop(pairs, dataSize, renegotiate = false)
    report.print()
  }

  // running tasks in non-blocking loop - with renegotiation
  @Test
  def testTasksInLoopWithRenegotiation(): Unit = {
    println("testTasksInExecutorWithRenegotiation():")
    val pairs = factory.nioNioN(None, totalConnections, None, None, None, None, runTasks = true)
    val report = NonBlockingLoops.loop(pairs, dataSize, renegotiate = true)
    assertEquals(0, report.asyncTasksRun)
    report.print()
  }

  // running tasks in executor - with renegotiation
  @Test
  def testTasksInExecutorWithRenegotiation(): Unit = {
    println("testTasksInExecutorWithRenegotiation():")
    val pairs = factory.nioNioN(None, totalConnections, None, None, None, None, runTasks = false)
    val report = NonBlockingLoops.loop(pairs, dataSize, renegotiate = true)
    report.print()
  }

  @AfterAll
  def afterAll() = {
    println(factory.getGlobalAllocationReport())
  }

}
