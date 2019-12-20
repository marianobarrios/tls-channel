package tlschannel;

import java.nio.ByteBuffer;

/**
 * Allocator that creates heap buffers. The {@link #free(ByteBuffer)} method is a no-op, as heap
 * buffers are handled completely by the garbage collector.
 *
 * <p>Direct buffers are generally used as a simple and generally good enough default solution.
 */
public class HeapBufferAllocator implements BufferAllocator {

  @Override
  public ByteBuffer allocate(int size) {
    return ByteBuffer.allocate(size);
  }

  @Override
  public void free(ByteBuffer buffer) {
    // GC does it
  }
}
