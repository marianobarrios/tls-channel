package tlschannel.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;


import tlschannel.TlsChannel;

public class ImmutableByteBufferSet implements ByteBufferSet
{
  private final ByteBuffer[] buffers;
  private final int offset;
  private final int length;

  public ImmutableByteBufferSet(ByteBuffer[] buffers, int offset, int length) {
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

  public ImmutableByteBufferSet(ByteBuffer[] buffers)
  {
    this(buffers, 0, buffers.length);
  }

  public ImmutableByteBufferSet(ByteBuffer buffer)
  {
    this(new ByteBuffer[]{buffer});
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
  public boolean hasRemaining()
  {
    return remaining() > 0;
  }

  @Override
  public boolean isReadOnly()
  {
    return ByteBufferSetUtil.isReadOnly(buffers, offset, length);
  }

  @Override
  public SSLEngineResult unwrap(final SSLEngine engine, final ByteBuffer buffer) throws SSLException
  {
    if (numberOfBuffersRemaining() == 1)
    {
      return engine.unwrap(buffer, buffers[offset]);
    }
    else
    {
      return engine.unwrap(buffer, buffers, offset, length);
    }
  }

  @Override
  public SSLEngineResult wrap(final SSLEngine engine, final ByteBuffer buffer) throws SSLException
  {
    if (numberOfBuffersRemaining() == 1)
    {
      return engine.wrap(buffers[offset], buffer);
    }
    else
    {
      return engine.wrap(buffers, offset, length, buffer);
    }
  }

  @Override
  public long read(final TlsChannel tlsChannel) throws IOException
  {
    if (numberOfBuffersRemaining() == 1)
    {
      return tlsChannel.read(buffers[offset]);
    }
    else
    {
      return tlsChannel.read(buffers, offset, length);
    }
  }

  public void write(final TlsChannel tlsChannel) throws IOException
  {
    if (numberOfBuffersRemaining() == 1)
    {
      tlsChannel.write(buffers[offset]);
    }
    else
    {
      tlsChannel.write(buffers, offset, length);
    }
  }

  private int numberOfBuffersRemaining()
  {
    return length - offset;
  }

  @Override
  public String toString()
  {
    return "ImmutableByteBufferSet[array="
           + Arrays.toString(buffers)
           + ", offset="
           + offset
           + ", length="
           + length
           + "]";
  }
}
