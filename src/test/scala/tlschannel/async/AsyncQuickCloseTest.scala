package tlschannel.async

import org.scalatest.Assertions
import org.scalatest.funsuite.AnyFunSuite
import tlschannel.helpers.{SocketPairFactory, SslContextFactory}

class AsyncQuickCloseTest extends AnyFunSuite with Assertions {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)

  /*
   * Closing sockets registered in an asynchronous channel group is inherently racy, using repetitions to try to catch
   * most races.
   */
  val repetitions = 1000

  // see https://github.com/marianobarrios/tls-channel/issues/34
  test("should not cause the selector thread to die") {
    val channelGroup = new AsynchronousTlsChannelGroup()
    for (_ <- 1 to repetitions) {
      val socketPair = factory.async(null, channelGroup, runTasks = true)
      socketPair.server.external.close()
      assert(channelGroup.isAlive)
    }
    channelGroup.shutdown()
  }

}
