package tlschannel.helpers

import java.nio.ByteBuffer
import com.typesafe.scalalogging.slf4j.StrictLogging
import org.scalatest.Matchers

object Loops extends Matchers with StrictLogging {

  def writerLoop(
    data: Array[Byte],
    socketGroup: SocketGroup,
    renegotiate: Boolean = false,
    scathering: Boolean = false): Unit = TestUtil.cannotFail("Error in writer") {

    val renegotiatePeriod = 10000
    logger.debug(s"Starting writer loop, renegotiate:$renegotiate")
    val originData = multiWrap(data)
    var bytesSinceRenegotiation = 0L
    while (remaining(originData) > 0) {
      if (renegotiate && bytesSinceRenegotiation > renegotiatePeriod) {
        socketGroup.tls.renegotiate()
        bytesSinceRenegotiation = 0
      }
      val c = if (scathering)
        socketGroup.tls.write(originData)
      else
        socketGroup.external.write(originData(1))

      bytesSinceRenegotiation += c
      assert(c > 0)
    }
    logger.debug("Finalizing writer loop")
  }

  def readerLoop(
    data: Array[Byte],
    socketGroup: SocketGroup,
    gathering: Boolean = false): Unit = TestUtil.cannotFail("Error in reader") {

    logger.debug("Starting reader loop")
    val receivedData = ByteBuffer.allocate(data.length)
    val receivedDataArray = Array(ByteBuffer.allocate(0), receivedData, ByteBuffer.allocate(0))
    while (remaining(receivedDataArray) > 0) {
      val c = if (gathering)
        socketGroup.tls.read(receivedDataArray)
      else
        socketGroup.external.read(receivedData)
      assert(c > 0, "blocking read must return a positive number")
    }
    assert(receivedData.array.deep === data.deep)
    logger.debug("Finalizing reader loop")
  }

  private def multiWrap(data: Array[Byte]) = {
    Array(ByteBuffer.allocate(0), ByteBuffer.wrap(data), ByteBuffer.allocate(0))
  }

  private def remaining(buffers: Array[ByteBuffer]) = {
    buffers.map(_.remaining.toLong).sum
  }

}