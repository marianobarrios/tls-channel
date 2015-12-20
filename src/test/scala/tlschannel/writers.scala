package tlschannel

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.net.Socket
import java.nio.channels.ByteChannel
import org.scalatest.Matchers

trait Writer {
  def renegotiate(): Unit = {}
  def write(array: Array[Byte], offset: Int, length: Int): Unit
  def close(): Unit
}

class SocketWriter(socket: Socket) extends Writer {

  val os = socket.getOutputStream

  def write(array: Array[Byte], offset: Int, length: Int) = {
    os.write(array, offset, length)
  }

  def close() = socket.close()

}

class SocketChannelWriter(socket: ByteChannel, rawSocket: SocketChannel) extends Writer with Matchers {

  def write(array: Array[Byte], offset: Int, length: Int) = {
    val buffer = ByteBuffer.wrap(array, offset, length)
    while (buffer.remaining() > 0) {
      val c = socket.write(buffer)
      assert(c != 0, "blocking write cannot return 0")
    }
  }

  def close() = socket.close()

}

class TlsSocketChannelWriter(socket: TlsSocketChannel, rawSocket: SocketChannel)
    extends SocketChannelWriter(socket, rawSocket) with Matchers {

  override def renegotiate() = socket.renegotiate()

}
