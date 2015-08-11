package tlschannel

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.net.Socket
import java.nio.channels.ByteChannel

trait Reader {
  def isBlocking: Boolean
  def read(array: Array[Byte], offset: Int, length: Int): Int
  def close(): Unit
}

class SocketReader(socket: Socket) extends Reader {
  
  def isBlocking = true
  
  val is = socket.getInputStream
  
  def read(array: Array[Byte], offset: Int, length: Int) = {
    val c = is.read(array, offset, length)
    if (length > 0)
      assert(c != 0, "blocking read cannot return 0")
    c
  }
  
  def close() = socket.close()
  
}

class ByteChannelReader(socket: ByteChannel, rawSocket: SocketChannel) extends Reader with Asserts {

  val isBlocking = rawSocket.isBlocking

  def read(array: Array[Byte], offset: Int, length: Int) = {
    intercept[IllegalArgumentException] {
      socket.read(ByteBuffer.allocate(1).asReadOnlyBuffer())
    }    
    if (isBlocking) {
      val c = socket.read(ByteBuffer.wrap(array, offset, length))
      if (length > 0)
        assert(c != 0, "blocking read cannot return 0")
      c
    } else {
      try {
        assertFasterThan(nonBlockingThresholdMs) {
          socket.read(ByteBuffer.wrap(array, offset, length))
        }
      } catch {
        case _: TlsNonBlockingNecessityException =>
          0
      }
    }
  }
  
  def close() = socket.close()
  
}

