package tlschannel.async

import org.junit.jupiter.api.{DynamicTest, TestFactory, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle
import tlschannel.helpers.Loops
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SocketPairFactory.{ChuckSizes, ChunkSizeConfig}
import tlschannel.helpers.SslContextFactory

import java.util
import java.util.Optional
import scala.jdk.CollectionConverters._

@TestInstance(Lifecycle.PER_CLASS)
class PseudoAsyncTest {

  val sslContextFactory = new SslContextFactory

  val channelGroup = new AsynchronousTlsChannelGroup()
  // TODO: close
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)
  val dataSize = 60 * 1000

  // test a half-duplex interaction, with renegotiation before reversing the direction of the flow (as in HTTP)
  @TestFactory
  def testHalfDuplex(): util.Collection[DynamicTest] = {
    val sizes = LazyList.iterate(1)(_ * 2).takeWhile(_ < SslContextFactory.tlsMaxDataSize * 2)
    val tests = for ((size1, size2) <- sizes zip sizes.reverse) yield {
      DynamicTest.dynamicTest(
        s"testHalfDuplex() - size1=$size1, size2=$size2",
        () => {
          val socketPair = factory.nioNio(
            chunkSizeConfig = Some(
              ChunkSizeConfig(
                ChuckSizes(Optional.of(size1), Optional.of(size2)),
                ChuckSizes(Optional.of(size1), Optional.of(size2))
              )
            ),
            pseudoAsyncGroup = Some(channelGroup)
          )
          Loops.halfDuplex(socketPair, dataSize)
        }
      )
    }
    tests.asJava
  }

  // test a full-duplex interaction, without any renegotiation
  @TestFactory
  def testFullDuplex(): util.Collection[DynamicTest] = {
    val sizes = LazyList.iterate(1)(_ * 2).takeWhile(_ < SslContextFactory.tlsMaxDataSize * 2)
    val tests = for ((size1, size2) <- sizes zip sizes.reverse) yield {
      DynamicTest.dynamicTest(
        s"testFullDuplex() - size1=$size1, size2=$size2",
        () => {
          val socketPair = factory.nioNio(
            chunkSizeConfig = Some(
              ChunkSizeConfig(
                ChuckSizes(Optional.of(size1), Optional.of(size2)),
                ChuckSizes(Optional.of(size1), Optional.of(size2))
              )
            ),
            pseudoAsyncGroup = Some(channelGroup)
          )
          Loops.fullDuplex(socketPair, dataSize)
        }
      )
    }
    tests.asJava
  }

}
