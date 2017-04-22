package tlschannel.helpers

import java.nio.ByteBuffer
import java.security.MessageDigest

import scala.util.Random

import org.scalatest.Matchers

import com.typesafe.scalalogging.slf4j.StrictLogging

import tlschannel.helpers.TestUtil.Memo

object Loops extends Matchers with StrictLogging {

  val seed = 143000953L
  val bufferSize = 20000
  val renegotiatePeriod = 10000
  val hashAlgorithm = "SHA-256"

  def writerLoop(
    size: Int,
    socketGroup: SocketGroup,
    renegotiate: Boolean = false,
    scattering: Boolean = false): Unit = TestUtil.cannotFail("Error in writer") {

    logger.debug(s"Starting writer loop, size: $size, scathering: $scattering, renegotiate:$renegotiate")
    val random = new Random(seed)
    var bytesSinceRenegotiation = 0
    var bytesRemaining = size
    while (bytesRemaining > 0) {
      val buffer = ByteBuffer.allocate(math.min(bufferSize, bytesRemaining))
      random.nextBytes(buffer.array)
      while (buffer.hasRemaining()) {
        if (renegotiate && bytesSinceRenegotiation > renegotiatePeriod) {
          socketGroup.tls.renegotiate()
          bytesSinceRenegotiation = 0
        }
        val c = if (scattering)
          socketGroup.tls.write(multiWrap(buffer)).toInt
        else
          socketGroup.external.write(buffer)
        assert(c > 0, "blocking write must return a positive number")
        bytesSinceRenegotiation += c
        bytesRemaining -= c.toInt
        assert(c > 0)
        assert(bytesRemaining >= 0)
      }
    }
    logger.debug("Finalizing writer loop")
  }

  def readerLoop(
    size: Int,
    socketGroup: SocketGroup,
    gathering: Boolean = false): Unit = TestUtil.cannotFail("Error in reader") {

    logger.debug(s"Starting reader loop. Size: $size, gathering: $gathering")
    val random = new Random(seed)
    val readArray = Array.ofDim[Byte](bufferSize)
    var bytesRemaining = size
    val digest = MessageDigest.getInstance(hashAlgorithm)
    while (bytesRemaining > 0) {
      val readBuffer = ByteBuffer.wrap(readArray, 0, math.min(bufferSize, bytesRemaining))
      val c = if (gathering)
        socketGroup.tls.read(multiWrap(readBuffer)).toInt
      else
        socketGroup.external.read(readBuffer)
      assert(c > 0, "blocking read must return a positive number")
      digest.update(readBuffer.array(), 0, readBuffer.position())
      bytesRemaining -= c
      assert(bytesRemaining >= 0)
    }
    val actual = digest.digest()
    assert(actual === expectedBytesHash(size))
    logger.debug("Finalizing reader loop")
  }

  val expectedBytesHash = Memo { (size: Int) =>
    val digest = MessageDigest.getInstance(hashAlgorithm)
    val random = new Random(seed)
    val array = Array.ofDim[Byte](size)
    random.nextBytes(array)
    digest.digest(array)
  }

  private def multiWrap(buffer: ByteBuffer) = {
    Array(ByteBuffer.allocate(0), buffer, ByteBuffer.allocate(0))
  }

  private def remaining(buffers: Array[ByteBuffer]) = {
    buffers.map(_.remaining.toLong).sum
  }

}