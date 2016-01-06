package tlschannel

import java.nio.ByteBuffer
import java.net.Socket
import java.nio.channels.ByteChannel
import org.scalatest.Matchers

trait Reader {
  def read(array: Array[Byte], offset: Int, length: Int): Int
  def close(): Unit
}

class SocketReader(socket: Socket) extends Reader {
  private val is = socket.getInputStream
  def read(array: Array[Byte], offset: Int, length: Int) = is.read(array, offset, length)
  def close() = socket.close()
}

class ByteChannelReader(socket: ByteChannel) extends Reader with Matchers {
  def read(array: Array[Byte], offset: Int, length: Int) = socket.read(ByteBuffer.wrap(array, offset, length))
  def close() = socket.close()
}

