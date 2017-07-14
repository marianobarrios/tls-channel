package tlschannel

import org.scalatest.FunSuite
import org.scalatest.Matchers
import com.typesafe.scalalogging.StrictLogging
import tlschannel.helpers.Loops
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SslContextFactory
import tlschannel.helpers.TestUtil

class ScatteringTest extends FunSuite with Matchers with StrictLogging {

  val sslContextFactory = new SslContextFactory
  val (cipher, sslContext) = sslContextFactory.standardCipher
  val factory = new SocketPairFactory(sslContext)
 
  val dataSize = 150 * 1000

  test("half duplex") {
    val (cipher, _) = sslContextFactory.standardCipher
    val socketPair = factory.nioNio(cipher)
    val elapsed = TestUtil.time {
      Loops.halfDuplex(socketPair, dataSize, scattering = true)
    }
    info(f"$elapsed%5s")
  }

}

