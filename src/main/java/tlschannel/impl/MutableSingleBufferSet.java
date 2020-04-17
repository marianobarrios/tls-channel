package tlschannel.impl;

import java.io.IOException;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import tlschannel.TlsChannel;

public class MutableSingleBufferSet implements ByteBufferSet {
  private ByteBuffer buffer;

  public MutableSingleBufferSet wrap(final ByteBuffer byteBuffer) {
    this.buffer = byteBuffer;
    return this;
  }

  @Override
  public long remaining() {
    return buffer.remaining();
  }

  @Override
  public int putRemaining(final ByteBuffer from) {
    int bytes = Math.min(from.remaining(), buffer.remaining());
    ByteBufferUtil.copy(from, buffer, bytes);
    return bytes;
  }

  @Override
  public ByteBufferSet put(final ByteBuffer from, final int length) {
    if (from.remaining() < length) {
      throw new IllegalArgumentException();
    }
    if (buffer.remaining() < length) {
      throw new IllegalArgumentException();
    }
    if (length != 0) {
      int bytes = Math.min(length, from.remaining());
      ByteBufferUtil.copy(from, buffer, bytes);
    }
    return this;
  }

  @Override
  public int getRemaining(final ByteBuffer dst) {
    if (!dst.hasRemaining()) {
      return 0;
    }
    int bytes = Math.min(dst.remaining(), buffer.remaining());
    ByteBufferUtil.copy(buffer, dst, bytes);
    return bytes;
  }

  @Override
  public ByteBufferSet get(final ByteBuffer dst, final int length) {
    if (buffer.remaining() < length) {
      throw new IllegalArgumentException();
    }
    if (dst.remaining() < length) {
      throw new IllegalArgumentException();
    }
    if (length != 0) {
      ByteBufferUtil.copy(buffer, dst, Math.min(length, buffer.remaining()));
    }
    return this;
  }

  @Override
  public boolean hasRemaining() {
    return buffer.hasRemaining();
  }

  @Override
  public boolean isReadOnly() {
    return buffer.isReadOnly();
  }

  @Override
  public SSLEngineResult unwrap(final SSLEngine engine, final ByteBuffer buffer)
      throws SSLException {
    return engine.unwrap(buffer, this.buffer);
  }

  @Override
  public SSLEngineResult wrap(final SSLEngine engine, final ByteBuffer buffer) throws SSLException {
    return engine.wrap(this.buffer, buffer);
  }

  @Override
  public long read(final TlsChannel tlsChannel) throws IOException {
    return tlsChannel.read(buffer);
  }

  @Override
  public void write(final TlsChannel tlsChannel) throws IOException {
    tlsChannel.write(buffer);
  }
}
