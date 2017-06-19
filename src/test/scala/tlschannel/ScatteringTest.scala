package tlschannel

import org.scalatest.FunSuite
import org.scalatest.Matchers

import com.typesafe.scalalogging.slf4j.StrictLogging

import tlschannel.helpers.Loops
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SslContextFactory
import tlschannel.helpers.TestUtil
import tlschannel.helpers.TestUtil.StreamWithTakeWhileInclusive

class ScatteringTest extends FunSuite with Matchers with StrictLogging {

  val (cipher, sslContext) = SslContextFactory.standardCipher
  val factory = new SocketPairFactory(sslContext, SslContextFactory.certificateCommonName)
 
  val dataSize = 150 * 1000

  test("half duplex") {
    val (cipher, _) = SslContextFactory.standardCipher
    val socketPair = factory.nioNio(cipher)
    val elapsed = TestUtil.time {
      Loops.halfDuplex(socketPair, dataSize, scattering = true)
    }
    info(f"${elapsed / 1000}%5d ms")
  }

}

