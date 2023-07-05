package tlschannel

import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle
import tlschannel.helpers.Loops
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SslContextFactory

@TestInstance(Lifecycle.PER_CLASS)
class ScatteringTest {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)

  val dataSize = 150 * 1000

  @Test
  def testHalfDuplex(): Unit = {
    val socketPair = factory.nioNio()
    Loops.halfDuplex(socketPair, dataSize, scattering = true)
  }

}
