package tlschannel

import com.typesafe.scalalogging.StrictLogging
import org.junit.jupiter.api.{DynamicTest, TestFactory, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle
import tlschannel.helpers.NonBlockingLoops
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SslContextFactory
import tlschannel.helpers.TestUtil.LazyListWithTakeWhileInclusive
import scala.jdk.CollectionConverters._
import java.util

@TestInstance(Lifecycle.PER_CLASS)
class NonBlockingTest extends StrictLogging {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)
  val dataSize = 200 * 1024

  @TestFactory
  def testSelectorLoop(): util.Collection[DynamicTest] = {
    println("testSelectorLoop():")
    val sizes = LazyList.iterate(1)(_ * 4).takeWhileInclusive(_ <= SslContextFactory.tlsMaxDataSize)
    val tests = for ((size1, size2) <- sizes zip sizes.reverse) yield {
      DynamicTest.dynamicTest(
        s"testSelectorLoop() - size1=$size1, size2=$size2",
        () => {
          val socketPair = factory.nioNio(
            internalClientChunkSize = Some(size1),
            externalClientChunkSize = Some(size2),
            internalServerChunkSize = Some(size1),
            externalServerChunkSize = Some(size2)
          )
          val report = NonBlockingLoops.loop(Seq(socketPair), dataSize, renegotiate = true)
          println(f"** $size1%d -eng-> $size2%d -net-> $size1%d -eng-> $size2%d **")
          report.print()
        }
      )
    }
    tests.asJava
  }

}
