package tlschannel.helpers

import java.nio.ByteBuffer
import java.nio.channels.AsynchronousByteChannel
import java.nio.channels.ByteChannel

import tlschannel.WouldBlockException

class BlockerByteChannel(impl: AsynchronousByteChannel) extends ByteChannel {

  override def write(src: ByteBuffer) = impl.write(src).get()

  override def read(dst: ByteBuffer) = impl.read(dst).get()

  override def isOpen = impl.isOpen

  override def close() = {
    try {
      impl.close()
    } catch {
      case _: WouldBlockException => // OK
    }
  }

}
