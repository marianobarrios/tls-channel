package tlschannel

import org.scalatest.{Assertions, BeforeAndAfterAll}
import com.typesafe.scalalogging.StrictLogging
import org.scalatest.funsuite.AnyFunSuite
import tlschannel.helpers.TestUtil.LazyListWithTakeWhileInclusive
import tlschannel.helpers.TestUtil
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.Loops
import tlschannel.helpers.SslContextFactory

/**
  * Test using a null engine (pass-through).	The purpose of the test is to remove
  * the overhead of the real [[javax.net.ssl.SSLEngine]] to be able to test the overhead of the
  * [[TlsChannel]].
  */
class NullEngineTest extends AnyFunSuite with Assertions with StrictLogging with BeforeAndAfterAll {

  val sslContextFactory = new SslContextFactory
  val (cipher, sslContext) = sslContextFactory.standardCipher
  val factory = new SocketPairFactory(sslContext)
  val dataSize = 1 * 200 * 1024 * 1024

  // heat cache
  Loops.expectedBytesHash(dataSize)

  test("null engine - half duplex - heap buffers") {
    val sizes = LazyList.iterate(512)(_ * 3).takeWhileInclusive(_ <= SslContextFactory.tlsMaxDataSize)
    val elapsedTotal = TestUtil.time {
      for (size1 <- sizes) {
        logger.debug(s"Testing sizes: size1=$size1")
        val socketPair =
          factory.nioNio(cipher = null, internalClientChunkSize = Some(size1), internalServerChunkSize = Some(size1))
        val elapsed = TestUtil.time {
          Loops.halfDuplex(socketPair, dataSize)
        }
        info(f"-eng-> $size1%5d -net-> $size1%5d -eng-> - ${elapsed.toMillis}%5s ms")
      }
    }
    info(f"Total time: $elapsedTotal%5s")
  }

  test("null engine - half duplex - direct buffers") {
    val sizes = LazyList.iterate(512)(_ * 3).takeWhileInclusive(_ <= SslContextFactory.tlsMaxDataSize)
    val elapsedTotal = TestUtil.time {
      for (size1 <- sizes) {
        logger.debug(s"Testing sizes: size1=$size1")
        val socketPair =
          factory.nioNio(cipher = null, internalClientChunkSize = Some(size1), internalServerChunkSize = Some(size1))
        val elapsed = TestUtil.time {
          Loops.halfDuplex(socketPair, dataSize)
        }
        info(f"-eng-> $size1%5d -net-> $size1%5d -eng-> - ${elapsed.toMillis}%5s ms")
      }
    }
    info(f"Total time: ${elapsedTotal.toMillis}%5s ms")
  }

  override def afterAll() = {
    factory.printGlobalAllocationReport()
  }

}
