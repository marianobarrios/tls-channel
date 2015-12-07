package tlschannel

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.net.Socket
import java.nio.channels.ByteChannel
import org.scalatest.Matchers

trait Reader {
  def read(array: Array[Byte], offset: Int, length: Int): Int
  def close(): Unit
}

class SocketReader(socket: Socket) extends Reader {

  val is = socket.getInputStream

  def read(array: Array[Byte], offset: Int, length: Int) = {
    val c = is.read(array, offset, length)
    if (length > 0)
      assert(c != 0, "blocking read cannot return 0")
    c
  }

  def close() = socket.close()

}

class ByteChannelReader(socket: ByteChannel, rawSocket: SocketChannel) extends Reader with Matchers {

  def read(array: Array[Byte], offset: Int, length: Int) = {
    intercept[IllegalArgumentException] {
      socket.read(ByteBuffer.allocate(1).asReadOnlyBuffer())
    }
    val c = socket.read(ByteBuffer.wrap(array, offset, length))
    if (length > 0)
      assert(c != 0, "blocking read cannot return 0")
    c
  }

  def close() = socket.close()

}

