package tlschannel.impl;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class MutableByteBufferSet implements ByteBufferSet
{
  private ByteBuffer[] buffers;
  private int offset;
  private int length;

  public MutableByteBufferSet(ByteBuffer[] buffers, int offset, int length) {
    if (buffers == null) throw new NullPointerException();
    if (buffers.length < offset) throw new IndexOutOfBoundsException();
    if (buffers.length < offset + length) throw new IndexOutOfBoundsException();
    for (int i = offset; i < offset + length; i++) {
      if (buffers[i] == null) throw new NullPointerException();
    }
    this.buffers = buffers;
    this.offset = offset;
    this.length = length;
  }

  public MutableByteBufferSet(ByteBuffer[] buffers) {
    this(buffers, 0, buffers.length);
  }

  public MutableByteBufferSet(ByteBuffer buffer) {
    this(new ByteBuffer[] {buffer});
  }

  @Override
  public ByteBuffer[] getBuffers()
  {
    return buffers;
  }

  @Override
  public int getOffset()
  {
    return offset;
  }

  @Override
  public int getLength()
  {
    return length;
  }

  @Override
  public long remaining() {
    return ByteBufferSetUtil.remaining(buffers, offset, length);
  }

  @Override
  public int putRemaining(ByteBuffer from) {
    return ByteBufferSetUtil.putRemaining(from, buffers, offset, length);
  }

  @Override
  public ByteBufferSet put(ByteBuffer from, int length) {
    ByteBufferSetUtil.put(from, length, buffers, offset, this.length, remaining());
    return this;
  }

  @Override
  public int getRemaining(ByteBuffer dst) {
    return ByteBufferSetUtil.getRemaining(dst, buffers, offset, length);
  }

  @Override
  public ByteBufferSet get(ByteBuffer dst, int length) {
    ByteBufferSetUtil.get(dst, length, remaining(), buffers, offset, this.length);
    return this;
  }

  @Override
  public boolean hasRemaining() {
    return remaining() > 0;
  }

  @Override
  public boolean isReadOnly() {
    return ByteBufferSetUtil.isReadOnly(buffers, offset, length);
  }

  @Override
  public String toString() {
    return "ImmutableByteBufferSet[array="
        + Arrays.toString(buffers)
        + ", offset="
        + offset
        + ", length="
        + length
        + "]";
  }
}
