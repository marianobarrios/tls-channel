package tlschannel.helpers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.util.function.IntSupplier;

class RandomChunkingByteChannel implements ByteChannel {

  private final ByteChannel wrapped;
  private final IntSupplier chunkSizeSupplier;

  public RandomChunkingByteChannel(ByteChannel wrapped, IntSupplier chunkSizeSupplier) {
    this.wrapped = wrapped;
    this.chunkSizeSupplier = chunkSizeSupplier;
  }

  @Override
  public void close() throws IOException {
    wrapped.close();
  }

  @Override
  public boolean isOpen() {
    return wrapped.isOpen();
  }

  @Override
  public int read(ByteBuffer in) throws IOException {
    if (!in.hasRemaining()) {
      return 0;
    }
    int oldLimit = in.limit();
    try {
      int readSize = Math.min(chunkSizeSupplier.getAsInt(), in.remaining());
      in.limit(in.position() + readSize);
      return wrapped.read(in);
    } finally {
      in.limit(oldLimit);
    }
  }

  @Override
  public int write(ByteBuffer out) throws IOException {
    if (!out.hasRemaining()) {
      return 0;
    }
    int oldLimit = out.limit();
    try {
      int writeSize = Math.min(chunkSizeSupplier.getAsInt(), out.remaining());
      out.limit(out.position() + writeSize);
      return wrapped.write(out);
    } finally {
      out.limit(oldLimit);
    }
  }

}
