package tlschannel

import org.junit.jupiter.api.{AfterAll, DynamicTest, TestFactory, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.Loops
import tlschannel.helpers.SocketPairFactory.{ChuckSizes, ChunkSizeConfig}
import tlschannel.helpers.SslContextFactory

import scala.jdk.CollectionConverters._
import java.util
import java.util.logging.Logger

/** Test using a null engine (pass-through). The purpose of the test is to remove the overhead of the real
  * [[javax.net.ssl.SSLEngine]] to be able to test the overhead of the [[TlsChannel]].
  */
@TestInstance(Lifecycle.PER_CLASS)
class NullEngineTest {

  val logger = Logger.getLogger(classOf[NullEngineTest].getName)

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)
  val dataSize = 1 * 200 * 1024 * 1024

  // heat cache
  Loops.expectedBytesHash(dataSize)

  // null engine - half duplex - heap buffers
  @TestFactory
  def testHalfDuplexHeapBuffers(): util.Collection[DynamicTest] = {
    println("testHalfDuplexHeapBuffers():")
    val sizes = LazyList.iterate(512)(_ * 2).takeWhile(_ < SslContextFactory.tlsMaxDataSize * 2)
    val tests = for (size1 <- sizes) yield {
      DynamicTest.dynamicTest(
        s"testHalfDuplexHeapBuffers() - size1=$size1",
        () => {
          val socketPair =
            factory.nioNio(
              cipher = null,
              chunkSizeConfig = Some(
                ChunkSizeConfig(
                  ChuckSizes(Some(size1), None),
                  ChuckSizes(Some(size1), None)
                )
              )
            )
          Loops.halfDuplex(socketPair, dataSize)
          println(f"-eng-> $size1%5d -net-> $size1%5d -eng->")
        }
      )
    }
    tests.asJava
  }

  // null engine - half duplex - direct buffers
  @TestFactory
  def testHalfDuplexDirectBuffers(): util.Collection[DynamicTest] = {
    println("testHalfDuplexDirectBuffers():")
    val sizes = LazyList.iterate(512)(_ * 2).takeWhile(_ < SslContextFactory.tlsMaxDataSize * 2)
    val tests = for (size1 <- sizes) yield {
      DynamicTest.dynamicTest(
        s"Testing sizes: size1=$size1",
        () => {
          logger.fine(() => s"Testing sizes: size1=$size1")
          val socketPair =
            factory.nioNio(
              cipher = null,
              chunkSizeConfig = Some(
                ChunkSizeConfig(
                  ChuckSizes(Some(size1), None),
                  ChuckSizes(Some(size1), None)
                )
              )
            )
          Loops.halfDuplex(socketPair, dataSize)
          println(f"-eng-> $size1%5d -net-> $size1%5d -eng->")
        }
      )
    }
    tests.asJava
  }

  @AfterAll
  def afterAll() = {
    println(factory.getGlobalAllocationReport())
  }

}
