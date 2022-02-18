package tlschannel

import com.typesafe.scalalogging.StrictLogging
import org.scalatest.Assertions
import org.scalatest.funsuite.AnyFunSuite
import tlschannel.helpers.TestUtil.LazyListWithTakeWhileInclusive
import tlschannel.helpers.TestUtil
import tlschannel.helpers.SslContextFactory
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.Loops

class BlockingTest extends AnyFunSuite with Assertions with StrictLogging {

  val sslContextFactory = new SslContextFactory

  val factory = new SocketPairFactory(sslContextFactory.defaultContext)
  val dataSize = 60 * 1000

  /** Test a half-duplex interaction, with renegotiation before reversing the direction of the flow (as in HTTP)
    */
  test("half duplex (with renegotiations)") {
    val sizes = LazyList.iterate(1)(_ * 3).takeWhileInclusive(_ <= SslContextFactory.tlsMaxDataSize)
    for ((size1, size2) <- sizes zip sizes.reverse) {
      logger.debug(s"Testing sizes: size1=$size1,size2=$size2")
      val socketPair = factory.nioNio(
        internalClientChunkSize = Some(size1),
        externalClientChunkSize = Some(size2),
        internalServerChunkSize = Some(size1),
        externalServerChunkSize = Some(size2)
      )
      val elapsed = TestUtil.time {
        Loops.halfDuplex(socketPair, dataSize, renegotiation = true)
      }
      info(f"$size1%5d -eng-> $size2%5d -net-> $size1%5d -eng-> $size2%5d - ${elapsed.toMillis}%5d ms")
    }
  }

  /** Test a full-duplex interaction, without any renegotiation
    */
  test("full duplex") {
    val sizes = LazyList.iterate(1)(_ * 3).takeWhileInclusive(_ <= SslContextFactory.tlsMaxDataSize)
    for ((size1, size2) <- sizes zip sizes.reverse) {
      logger.debug(s"Testing sizes: size1=$size1,size2=$size2")
      val socketPair = factory.nioNio(
        internalClientChunkSize = Some(size1),
        externalClientChunkSize = Some(size2),
        internalServerChunkSize = Some(size1),
        externalServerChunkSize = Some(size2)
      )
      val elapsed = TestUtil.time {
        Loops.fullDuplex(socketPair, dataSize)
      }
      info(f"$size1%5d -eng-> $size2%5d -net-> $size1%5d -eng-> $size2%5d - ${elapsed.toMillis}%5d ms")
    }
  }

}
