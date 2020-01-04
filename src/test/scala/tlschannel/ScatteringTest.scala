package tlschannel

import org.scalatest.Assertions
import com.typesafe.scalalogging.StrictLogging
import org.junit.runner.RunWith
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner
import tlschannel.helpers.Loops
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SslContextFactory
import tlschannel.helpers.TestUtil

@RunWith(classOf[JUnitRunner])
class ScatteringTest extends AnyFunSuite with Assertions with StrictLogging {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)

  val dataSize = 150 * 1000

  test("half duplex") {
    val socketPair = factory.nioNio()
    val elapsed = TestUtil.time {
      Loops.halfDuplex(socketPair, dataSize, scattering = true)
    }
    info(f"$elapsed%5s")
  }

}
