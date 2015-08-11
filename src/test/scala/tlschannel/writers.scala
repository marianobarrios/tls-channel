package tlschannel

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.net.Socket
import java.nio.channels.ByteChannel

trait Writer {
  def isBlocking: Boolean
  def renegotiate(): Unit = {}
  def write(array: Array[Byte], offset: Int, length: Int): Unit
  def close(): Unit
}

class SocketWriter(socket: Socket) extends Writer {
  
  def isBlocking = true
  
  val os = socket.getOutputStream
  
  def write(array: Array[Byte], offset: Int, length: Int) = {
    os.write(array, offset, length)
  }
  
  def close() = socket.close()
  
}

class SocketChannelWriter(socket: ByteChannel, rawSocket: SocketChannel) extends Writer with Asserts {

  val isBlocking = rawSocket.isBlocking

  def write(array: Array[Byte], offset: Int, length: Int) = {
    var written = 0
    val buffer = ByteBuffer.wrap(array, offset, length)
    if (isBlocking) {
      while (written < length) {
        val c = socket.write(buffer)
        assert(c != 0, "blocking write cannot return 0")
        written += c
      }
    } else {
      while (written < length) {
        try {
          val c = assertFasterThan(nonBlockingThresholdMs) {
            socket.write(buffer)
          }
          if (c == 0)
            Thread.sleep(ioWaitMs)
          written += c
        } catch {
          case _: TlsNonBlockingNecessityException => Thread.sleep(ioWaitMs)
        }
      }
    }
    assert(written == length)
    assert(buffer.remaining == 0)
  }
  
  def close() = socket.close()

}

class TlsSocketChannelWriter(socket: TlsSocketChannel, rawSocket: SocketChannel) 
    extends SocketChannelWriter(socket, rawSocket) with Asserts {

  override def renegotiate(): Unit = {
    while (true) {
      try {
        socket.renegotiate()
        return
      } catch {
        case _: TlsNonBlockingNecessityException =>
          Thread.sleep(ioWaitMs)
      }
    }
  }

}
