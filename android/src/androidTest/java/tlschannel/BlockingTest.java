package tlschannel

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.runner.RunWith;
import tlschannel.helpers.TestUtil.LazyListWithTakeWhileInclusive;
import tlschannel.helpers.TestUtil;
import tlschannel.helpers.SslContextFactory;
import tlschannel.helpers.SocketPairFactory;
import tlschannel.helpers.Loops;

@RunWith(AndroidJUnit4.class)
public class BlockingTest {

  SslContextFactory sslContextFactory = new SslContextFactory();

  SocketPairFactory factory = new SocketPairFactory(sslContextFactory.defaultContext);
  private static final int dataSize = 60 * 1000;

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
