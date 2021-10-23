package tlschannel.impl;

import java.nio.ByteBuffer;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tlschannel.BufferAllocator;

public class BufferHolder {

  private static final Logger logger = LoggerFactory.getLogger(BufferHolder.class);
  private static final byte[] zeros = new byte[TlsChannelImpl.maxTlsPacketSize];

  public final String name;
  public final BufferAllocator allocator;
  public final boolean plainData;
  public final int maxSize;
  public final boolean opportunisticDispose;

  public ByteBuffer buffer;
  public int lastSize;

  public BufferHolder(
      String name,
      Optional<ByteBuffer> buffer,
      BufferAllocator allocator,
      int initialSize,
      int maxSize,
      boolean plainData,
      boolean opportunisticDispose) {
    this.name = name;
    this.allocator = allocator;
    this.buffer = buffer.orElse(null);
    this.maxSize = maxSize;
    this.plainData = plainData;
    this.opportunisticDispose = opportunisticDispose;
    this.lastSize = buffer.map(b -> b.capacity()).orElse(initialSize);
  }

  public void prepare() {
    if (buffer == null) {
      buffer = allocator.allocate(lastSize);
    }
  }

  public boolean release() {
    if (opportunisticDispose && buffer.position() == 0) {
      return dispose();
    } else {
      return false;
    }
  }

  public boolean dispose() {
    if (buffer != null) {
      allocator.free(buffer);
      buffer = null;
      return true;
    } else {
      return false;
    }
  }

  public void enlarge() {
    if (buffer.capacity() >= maxSize) {
      throw new IllegalStateException(
          String.format(
              "%s buffer insufficient despite having capacity of %d", name, buffer.capacity()));
    }
    int newCapacity = Math.min(buffer.capacity() * 2, maxSize);
    logger.trace(
        "enlarging buffer {}, increasing from {} to {}", name, buffer.capacity(), newCapacity);
    resize(newCapacity);
  }

  private void resize(int newCapacity) {
    ByteBuffer newBuffer = allocator.allocate(newCapacity);
    buffer.flip();
    newBuffer.put(buffer);
    if (plainData) {
      zero();
    }
    allocator.free(buffer);
    buffer = newBuffer;
    lastSize = newCapacity;
  }

  /**
   * Fill with zeros the remaining of the supplied buffer. This method does not change the buffer
   * position.
   *
   * <p>Typically used for security reasons, with buffers that contains now-unused plaintext.
   */
  public void zeroRemaining() {
    zero(buffer.position());
  }

  /**
   * Fill the buffer with zeros. This method does not change the buffer position.
   *
   * <p>Typically used for security reasons, with buffers that contains now-unused plaintext.
   */
  public void zero() {
    zero(0);
  }

  private void zero(final int position) {
    buffer.mark();
    buffer.position(position);
    int size = buffer.remaining();
    int length = Math.min(size, zeros.length);
    int offset = 0;
    while (length > 0) {
      buffer.put(zeros, 0, length);
      offset = offset + length;
      length = Math.min(size - offset, zeros.length);
    }
    buffer.reset();
  }

  public boolean nullOrEmpty() {
    return buffer == null || buffer.position() == 0;
  }

  @Override
  public String toString() {
    return "BufferHolder{"
        + "name='"
        + name
        + '\''
        + ", allocator="
        + allocator
        + ", plainData="
        + plainData
        + ", maxSize="
        + maxSize
        + ", opportunisticDispose="
        + opportunisticDispose
        + ", buffer="
        + buffer
        + ", lastSize="
        + lastSize
        + '}';
  }
}
