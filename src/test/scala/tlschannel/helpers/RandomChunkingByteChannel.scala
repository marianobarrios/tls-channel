package tlschannel.helpers

import java.nio.ByteBuffer
import java.nio.channels.ByteChannel

class RandomChunkingByteChannel(val wrapped: ByteChannel, chunkSizeSupplier: () => Int) extends ByteChannel {
  
  def close() = wrapped.close()
  def isOpen() = wrapped.isOpen()

  def read(in: ByteBuffer): Int = {
    if (!in.hasRemaining)
      return 0
    val oldLimit = in.limit()
    try {
      val readSize = math.min(chunkSizeSupplier(), in.remaining())
      in.limit(in.position() + readSize)
      wrapped.read(in)
    } finally {
      in.limit(oldLimit)
    }
  }

  def write(out: ByteBuffer): Int = {
    if (!out.hasRemaining)
      return 0
    val oldLimit = out.limit()
    try {
      val writeSize = math.min(chunkSizeSupplier(), out.remaining())
      out.limit(out.position() + writeSize)
      wrapped.write(out)
    } finally {
      out.limit(oldLimit)
    }
  }
  
}