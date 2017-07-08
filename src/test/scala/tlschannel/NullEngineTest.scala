package tlschannel

import org.scalatest.{BeforeAndAfterAll, ConfigMap, FunSuite, Matchers}
import com.typesafe.scalalogging.slf4j.StrictLogging

import tlschannel.helpers.TestUtil.StreamWithTakeWhileInclusive

import tlschannel.helpers.TestUtil
import tlschannel.helpers.SslContextFactory
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.Loops

/**
 * Test using a null engine (pass-through).	The purpose of the test is to remove
 * the overhead of the real [[javax.net.ssl.SSLEngine]] to be able to test the overhead of the
 * [[TlsChannel]].
 */
class NullEngineTest extends FunSuite with Matchers with StrictLogging with BeforeAndAfterAll {

  val (cipher, sslContext) = SslContextFactory.standardCipher
  val factory = new SocketPairFactory(sslContext, SslContextFactory.certificateCommonName)
  val dataSize = 1 * 200 * 1024 * 1024

  // heat cache
  Loops.expectedBytesHash(dataSize)

  test("null engine - half duplex - heap buffers") {
    val sizes = Stream.iterate(512)(_ * 3).takeWhileInclusive(_ <= SslContextFactory.tlsMaxDataSize)
    val elapsedTotal = TestUtil.time {
      for (size1 <- sizes) {
        logger.debug(s"Testing sizes: size1=$size1")
        val socketPair = factory.nullNioNio(
          internalClientChunkSize = Some(size1),
          internalServerChunkSize = Some(size1),
          factory.globalPlainTrackingAllocator)
        val elapsed = TestUtil.time {
          Loops.halfDuplex(socketPair, dataSize)
        }
        info(f"-eng-> $size1%5d -net-> $size1%5d -eng-> - ${elapsed / 1000}%5d ms")
      }
    }
    info(f"Total time: ${elapsedTotal / 1000}%5d ms")
  }

  test("null engine - half duplex - direct buffers") {
    val sizes = Stream.iterate(512)(_ * 3).takeWhileInclusive(_ <= SslContextFactory.tlsMaxDataSize)
    val elapsedTotal = TestUtil.time {
      for (size1 <- sizes) {
        logger.debug(s"Testing sizes: size1=$size1")
        val socketPair = factory.nullNioNio(
          internalClientChunkSize = Some(size1),
          internalServerChunkSize = Some(size1),
          factory.globalEncryptedTrackingAllocator)
        val elapsed = TestUtil.time {
          Loops.halfDuplex(socketPair, dataSize)
        }
        info(f"-eng-> $size1%5d -net-> $size1%5d -eng-> - ${elapsed / 1000}%5d ms")
      }
    }
    info(f"Total time: ${elapsedTotal / 1000}%5d ms")
  }

  override def afterAll(configMap: ConfigMap) = {
    factory.printGlobalAllocationReport()
  }

}

