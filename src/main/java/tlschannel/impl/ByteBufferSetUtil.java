package tlschannel.impl;

import java.nio.ByteBuffer;

public class ByteBufferSetUtil {
  static long remaining(final ByteBuffer[] buffers, final int offset, final int length) {
    long ret = 0;
    for (int i = offset; i < offset + length; i++) {
      ret += buffers[i].remaining();
    }
    return ret;
  }

  static int putRemaining(
      final ByteBuffer from, final ByteBuffer[] buffers, final int offset, final int length) {
    int totalBytes = 0;
    for (int i = offset; i < offset + length; i++) {
      if (!from.hasRemaining()) break;
      ByteBuffer dstBuffer = buffers[i];
      int bytes = Math.min(from.remaining(), dstBuffer.remaining());
      ByteBufferUtil.copy(from, dstBuffer, bytes);
      totalBytes += bytes;
    }
    return totalBytes;
  }

  static void put(
      final ByteBuffer from,
      final int fromLength,
      final ByteBuffer[] buffers,
      final int offset,
      final int length,
      final long remaining) {
    if (from.remaining() < fromLength) {
      throw new IllegalArgumentException();
    }
    if (remaining < fromLength) {
      throw new IllegalArgumentException();
    }
    int totalBytes = 0;
    for (int i = offset; i < offset + length; i++) {
      int pending = fromLength - totalBytes;
      if (pending == 0) break;
      int bytes = Math.min(pending, (int) remaining);
      ByteBuffer dstBuffer = buffers[i];
      ByteBufferUtil.copy(from, dstBuffer, bytes);
      totalBytes += bytes;
    }
  }

  static int getRemaining(
      final ByteBuffer dst, final ByteBuffer[] buffers, final int offset, final int length) {
    int totalBytes = 0;
    for (int i = offset; i < offset + length; i++) {
      if (!dst.hasRemaining()) break;
      ByteBuffer srcBuffer = buffers[i];
      int bytes = Math.min(dst.remaining(), srcBuffer.remaining());
      ByteBufferUtil.copy(srcBuffer, dst, bytes);
      totalBytes += bytes;
    }
    return totalBytes;
  }

  static void get(
      final ByteBuffer dst,
      final int dstLength,
      final long remaining,
      final ByteBuffer[] buffers,
      final int offset,
      final int length) {
    if (remaining < dstLength) {
      throw new IllegalArgumentException();
    }
    if (dst.remaining() < dstLength) {
      throw new IllegalArgumentException();
    }
    int totalBytes = 0;
    for (int i = offset; i < offset + length; i++) {
      int pending = dstLength - totalBytes;
      if (pending == 0) break;
      ByteBuffer srcBuffer = buffers[i];
      int bytes = Math.min(pending, srcBuffer.remaining());
      ByteBufferUtil.copy(srcBuffer, dst, bytes);
      totalBytes += bytes;
    }
  }

  static boolean isReadOnly(final ByteBuffer[] buffers, final int offset, final int length) {
    for (int i = offset; i < offset + length; i++) {
      if (buffers[i].isReadOnly()) return true;
    }
    return false;
  }
}
