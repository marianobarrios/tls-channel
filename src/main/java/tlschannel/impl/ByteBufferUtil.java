package tlschannel.impl;

import java.nio.Buffer;
import java.nio.ByteBuffer;

public class ByteBufferUtil {

  public static void copy(ByteBuffer src, ByteBuffer dst, int length) {
    if (length < 0) {
      throw new IllegalArgumentException("negative length");
    }
    if (src.remaining() < length) {
      throw new IllegalArgumentException(
          String.format(
              "source buffer does not have enough remaining capacity (%d < %d)",
              src.remaining(), length));
    }
    if (dst.remaining() < length) {
      throw new IllegalArgumentException(
          String.format(
              "destination buffer does not have enough remaining capacity (%d < %d)",
              dst.remaining(), length));
    }
    if (length == 0) {
      return;
    }
    ByteBuffer tmp = src.duplicate();
    tmp.limit(((Buffer)src).position() + length);
    dst.put(tmp);
    ((Buffer)src).position(((Buffer)src).position() + length);
  }
}
