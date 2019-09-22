package tlschannel.helpers

import java.nio.ByteBuffer
import java.nio.channels.ByteChannel

class ChunkingByteChannel(val wrapped: ByteChannel, chunkSize: Int) extends ByteChannel {
  
  def close() = wrapped.close()
  def isOpen() = wrapped.isOpen()

  def read(in: ByteBuffer): Int = {
    if (!in.hasRemaining)
      return 0
    val oldLimit = in.limit()
    try {
      val readSize = math.min(chunkSize, in.remaining())
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
      val writeSize = math.min(chunkSize, out.remaining())
      out.limit(out.position() + writeSize)
      wrapped.write(out)
    } finally {
      out.limit(oldLimit)
    }
  }
  
}