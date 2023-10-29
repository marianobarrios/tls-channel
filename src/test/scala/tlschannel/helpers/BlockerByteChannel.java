package tlschannel.helpers;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousByteChannel;
import java.nio.channels.ByteChannel;
import java.util.concurrent.ExecutionException;

import tlschannel.WouldBlockException;

/**
 * Transforms a non-blocking {@link ByteChannel} into a blocking one.
 */
class BlockerByteChannel implements ByteChannel {

  private final AsynchronousByteChannel impl;

  public BlockerByteChannel(AsynchronousByteChannel impl) {
    this.impl = impl;
  }

  @Override
  public int write(ByteBuffer src)throws IOException {
    try {
      return impl.write(src).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public int read(ByteBuffer dst) throws IOException {
    try {
      return impl.read(dst).get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    } catch (ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public boolean isOpen() {
    return impl.isOpen();
  }

  @Override
  public void close() throws IOException {
    try {
      impl.close();
    } catch (WouldBlockException e) {
      // OK
    }
  }

}
