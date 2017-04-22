package tlschannel

import java.nio.ByteBuffer
import java.nio.channels.SelectionKey

import org.scalatest.FunSuite
import org.scalatest.Matchers

import com.typesafe.scalalogging.slf4j.StrictLogging

import tlschannel.helpers.NonBlockingLoops
import tlschannel.helpers.SocketGroup
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SslContextFactory
import tlschannel.helpers.TestUtil

class MultiNonBlockingTest extends FunSuite with Matchers with StrictLogging with NonBlockingSuite {

  val (cipher, sslContext) = SslContextFactory.standardCipher
  val factory = new SocketPairFactory(sslContext, SslContextFactory.certificateCommonName)
  val dataSize = SslContextFactory.tlsMaxDataSize * 5
  val totalConnections = 50

  test("running tasks in non-blocking loop - no renegotiation") {
    val pairs = factory.nioNioN(cipher, totalConnections, None, None, None, None, runTasks = true)
    val (report, elapsed) = TestUtil.time {
      NonBlockingLoops.testNonBlockingLoop(pairs, dataSize, renegotiate = false)
    }
    assert(report.asyncTasksRun === 0)
    printReport(report, elapsed)
  }

  test("running tasks in executor - no renegotiation") {
    val pairs = factory.nioNioN(cipher, totalConnections, None, None, None, None, runTasks = false)
    val (report, elapsed) = TestUtil.time {
      NonBlockingLoops.testNonBlockingLoop(pairs, dataSize, renegotiate = false)
    }
    printReport(report, elapsed)
  }

  test("running tasks in non-blocking loop - with renegotiation") {
    val pairs = factory.nioNioN(cipher, totalConnections, None, None, None, None, runTasks = true)
    val (report, elapsed) = TestUtil.time {
      NonBlockingLoops.testNonBlockingLoop(pairs, dataSize, renegotiate = true)
    }
    assert(report.asyncTasksRun === 0)
    printReport(report, elapsed)
  }

  test("running tasks in executor - with renegotiation") {
    val pairs = factory.nioNioN(cipher, totalConnections, None, None, None, None, runTasks = false)
    val (report, elapsed) = TestUtil.time {
      NonBlockingLoops.testNonBlockingLoop(pairs, dataSize, renegotiate = true)
    }
    printReport(report, elapsed)
  }

}