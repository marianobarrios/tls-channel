package tlschannel

import java.nio.channels.ByteChannel
import java.nio.ByteBuffer
import java.util.Random

/**
 * A special decorating ByteChannel that does not try to use the buffers passed as arguments completely.
 */
class ChunkingByteChannel(impl: ByteChannel) extends ByteChannel {

  def close() = impl.close()
  def isOpen() = impl.isOpen()

  private val random = new Random

  def read(in: ByteBuffer): Int = {
    if (!in.hasRemaining)
      return 0
    val oldLimit = in.limit
    try {
      val readSize = random.nextInt(in.remaining) + 1
      in.limit(in.position + readSize)
      impl.read(in)
    } finally {
      in.limit(oldLimit)
    }
  }

  def write(out: ByteBuffer): Int = {
    if (!out.hasRemaining)
      return 0
    val oldLimit = out.limit
    try {
      val writeSize = random.nextInt(out.remaining) + 1
      out.limit(out.position + writeSize)
      impl.write(out)
    } finally {
      out.limit(oldLimit)
    }
  }

}