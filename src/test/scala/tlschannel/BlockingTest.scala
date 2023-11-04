package tlschannel

import org.junit.jupiter.api.{DynamicTest, TestFactory, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle
import tlschannel.helpers.TestUtil.LazyListWithTakeWhileInclusive
import tlschannel.helpers.SslContextFactory
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.Loops
import tlschannel.helpers.SocketPairFactory.ChunkSizeConfig
import tlschannel.helpers.SocketPairFactory.ChuckSizes

import java.util
import java.util.logging.Logger
import scala.jdk.CollectionConverters._

@TestInstance(Lifecycle.PER_CLASS)
class BlockingTest {

  val logger = Logger.getLogger(classOf[BlockingTest].getName)

  val sslContextFactory = new SslContextFactory

  val factory = new SocketPairFactory(sslContextFactory.defaultContext)
  val dataSize = 60 * 1000

  // Test a half-duplex interaction, with renegotiation before reversing the direction of the flow (as in HTTP)
  @TestFactory
  def testHalfDuplexWireRenegotiations(): util.Collection[DynamicTest] = {
    println("testHalfDuplexWireRenegotiations():")
    val sizes = LazyList.iterate(1)(_ * 3).takeWhileInclusive(_ <= SslContextFactory.tlsMaxDataSize)
    val tests = for ((size1, size2) <- sizes zip sizes.reverse) yield {
      DynamicTest.dynamicTest(
        s"testHalfDuplexWireRenegotiations() - size1=$size1, size2=$size2",
        () => {
          val socketPair = factory.nioNio(
            chunkSizeConfig = Some(
              ChunkSizeConfig(
                ChuckSizes(Some(size1), Some(size2)),
                ChuckSizes(Some(size1), Some(size2))
              )
            )
          )
          Loops.halfDuplex(socketPair, dataSize, renegotiation = true)
          println(f"$size1%5d -eng-> $size2%5d -net-> $size1%5d -eng-> $size2%5d")
        }
      )
    }
    tests.asJava
  }

  // Test a full-duplex interaction, without any renegotiation
  @TestFactory
  def testFullDuplex(): util.Collection[DynamicTest] = {
    println("testFullDuplex():")
    val sizes = LazyList.iterate(1)(_ * 3).takeWhileInclusive(_ <= SslContextFactory.tlsMaxDataSize)
    val tests = for ((size1, size2) <- sizes zip sizes.reverse) yield {
      DynamicTest.dynamicTest(
        s"testFullDuplex() - size1=$size1,size2=$size2",
        () => {
          logger.fine(() => s"Testing sizes: size1=$size1,size2=$size2")
          val socketPair = factory.nioNio(chunkSizeConfig =
            Some(
              ChunkSizeConfig(
                ChuckSizes(Some(size1), Some(size2)),
                ChuckSizes(Some(size1), Some(size2))
              )
            )
          )
          Loops.fullDuplex(socketPair, dataSize)
          println(f"$size1%5d -eng-> $size2%5d -net-> $size1%5d -eng-> $size2%5d")
        }
      )
    }
    tests.asJava
  }

}
