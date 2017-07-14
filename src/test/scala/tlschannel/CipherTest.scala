package tlschannel

import org.scalatest.FunSuite
import org.scalatest.Matchers
import com.typesafe.scalalogging.StrictLogging
import java.nio.channels.ByteChannel
import tlschannel.helpers.TestUtil.StreamWithTakeWhileInclusive
import java.nio.ByteBuffer
import scala.util.Random
import tlschannel.helpers.TestUtil
import tlschannel.helpers.SslContextFactory
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SocketPair
import tlschannel.helpers.Loops

class CipherTest extends FunSuite with Matchers with StrictLogging {

  val protocols = Seq("TLSv1", "TLSv1.1", "TLSv1.2")

  val dataSize = 200 * 1000

  /**
    * Test a half-duplex interaction, with renegotiation before reversing the direction of the flow (as in HTTP)
    */
  test("half duplex (with renegotiations)") {
    for (protocol <- protocols) {
      val ctxFactory = new SslContextFactory(protocol)
      for ((cipher, sslContext) <- ctxFactory.allCiphers) {
        withClue(cipher + ": ") {
          logger.debug(s"Testing cipher: $cipher")
          val socketFactory = new SocketPairFactory(sslContext)
          val socketPair = socketFactory.nioNio(cipher)
          val elapsed = TestUtil.time {
            Loops.halfDuplex(socketPair, dataSize, renegotiation = true)
          }
          val protocol = socketPair.client.tls.getSslEngine.getSession.getProtocol
          info(f"$protocol%-12s $cipher%-50s ${elapsed.toMillis}%6s ms")
        }
      }
    }
  }

  /**
    * Test a full-duplex interaction, without any renegotiation
    */
  test("full duplex") {
    for (protocol <- protocols) {
      val ctxFactory = new SslContextFactory(protocol)
      for ((cipher, sslContext) <- ctxFactory.allCiphers) {
        withClue(cipher + ": ") {
          logger.debug(s"Testing cipher: $cipher")
          val socketFactory = new SocketPairFactory(sslContext)
          val socketPair = socketFactory.nioNio(cipher)
          val elapsed = TestUtil.time {
            Loops.fullDuplex(socketPair, dataSize)
          }
          val protocol = socketPair.client.tls.getSslEngine.getSession.getProtocol
          info(f"$protocol%-12s $cipher%-50s ${elapsed.toMillis}%6s ms")
        }
      }
    }
  }

}