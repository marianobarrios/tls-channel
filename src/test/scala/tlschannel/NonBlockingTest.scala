package tlschannel

import org.scalatest.FunSuite
import org.scalatest.Matchers

import com.typesafe.scalalogging.slf4j.StrictLogging

import tlschannel.helpers.NonBlockingLoops
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SslContextFactory
import tlschannel.helpers.TestUtil
import tlschannel.helpers.TestUtil.StreamWithTakeWhileInclusive

class NonBlockingTest extends FunSuite with Matchers with StrictLogging with NonBlockingSuite {

  val (cipher, sslContext) = SslContextFactory.standardCipher
  val factory = new SocketPairFactory(sslContext, SslContextFactory.certificateCommonName)
  val dataSize = 200 * 1024

  test("selector loop") {
    val sizes = Stream.iterate(1)(_ * 4).takeWhileInclusive(_ <= SslContextFactory.tlsMaxDataSize)
    for ((size1, size2) <- (sizes zip sizes.reverse)) {
      logger.debug(s"Sizes: size1=$size1,size2=$size2")
      val socketPair = factory.nioNio(
        cipher,
        internalClientChunkSize = Some(size1),
        externalClientChunkSize = Some(size2),
        internalServerChunkSize = Some(size1),
        externalServerChunkSize = Some(size2))
      val (report, elapsed) = TestUtil.time {
        NonBlockingLoops.loop(Seq(socketPair), dataSize, renegotiate = true)
      }
      info(f"** $size1%d -eng-> $size2%d -net-> $size1%d -eng-> $size2%d **")
      printReport(report, elapsed)
    }
  }

}