package tlschannel

import java.nio.ByteBuffer
import java.nio.channels.ByteChannel
import org.scalatest.Matchers
import javax.net.ssl.SSLSocket

trait Writer {
  def renegotiate(): Unit
  def write(array: Array[Byte], offset: Int, length: Int): Unit
  def close(): Unit
}

class SSLSocketWriter(socket: SSLSocket) extends Writer {
  private val os = socket.getOutputStream
  def write(array: Array[Byte], offset: Int, length: Int) = os.write(array, offset, length)
  def renegotiate() = socket.startHandshake()
  def close() = socket.close()
}

class TlsSocketChannelWriter(val socket: TlsSocketChannel) extends Writer with Matchers {

  def write(array: Array[Byte], offset: Int, length: Int) = {
    val buffer = ByteBuffer.wrap(array, offset, length)
    while (buffer.remaining() > 0) {
      val c = socket.write(buffer)
      assert(c != 0, "blocking write cannot return 0")
    }
  }
  
  def renegotiate(): Unit = socket.renegotiate()
  def close() = socket.close()

}
