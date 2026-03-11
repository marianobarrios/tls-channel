package tlschannel.impl;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;

/**
 * An ordered, bounded view over an array of {@link ByteBuffer}s, analogous to the scatter/gather
 * abstractions in {@link java.nio.channels.ScatteringByteChannel} and {@link
 * java.nio.channels.GatheringByteChannel}.
 *
 * <p>The active window is defined by {@link #offset} and {@link #length}: only buffers at indices
 * {@code [offset, offset + length)} are considered. Buffers outside this range are never accessed.
 *
 * <p>The intent of this class is to allow to use a set of buffers like a single, continuous one,
 * referred as the "combined buffer".
 *
 * <p>The buffers are not supposed to be manipulated externally after used to construct a set.
 */
public class ByteBufferSet {

    /** The underlying array of buffers. Never {@code null}, and no element in the active window is {@code null}. */
    public final ByteBuffer[] array;

    /** The index of the first buffer in the active window. */
    public final int offset;

    /** The number of buffers in the active window. */
    public final int length;

    /**
     * Creates a set backed by a slice of the given array.
     *
     * @param array  the buffer array; must not be {@code null}, and no element in the active window
     *               may be {@code null}
     * @param offset the index of the first active buffer
     * @param length the number of active buffers
     * @throws NullPointerException      if {@code array} is {@code null}, or any active element is
     *                                   {@code null}
     * @throws IndexOutOfBoundsException if {@code offset} or {@code offset + length} exceeds the
     *                                   array length
     */
    public ByteBufferSet(ByteBuffer[] array, int offset, int length) {
        if (array == null) {
            throw new NullPointerException();
        }
        if (array.length < offset) {
            throw new IndexOutOfBoundsException();
        }
        if (array.length < offset + length) {
            throw new IndexOutOfBoundsException();
        }
        for (int i = offset; i < offset + length; i++) {
            if (array[i] == null) {
                throw new NullPointerException();
            }
        }
        this.array = array;
        this.offset = offset;
        this.length = length;
    }

    /**
     * Creates a set backed by the entire given array.
     *
     * @param array the buffer array; must not be {@code null}, and no element may be {@code null}
     * @throws NullPointerException if {@code array} is {@code null} or any element is {@code null}
     */
    public ByteBufferSet(ByteBuffer[] array) {
        this(array, 0, array.length);
    }

    /**
     * Creates a set containing a single buffer.
     *
     * @param buffer the buffer; must not be {@code null}
     * @throws NullPointerException if {@code buffer} is {@code null}
     */
    public ByteBufferSet(ByteBuffer buffer) {
        this(new ByteBuffer[] {buffer});
    }

    /**
     * Returns the total number of bytes remaining across the combined buffer.
     */
    public long remaining() {
        long ret = 0;
        for (int i = offset; i < offset + length; i++) {
            ret += array[i].remaining();
        }
        return ret;
    }

    /**
     * Returns the total position across the combined buffer..
     */
    public long position() {
        long ret = 0;
        for (int i = offset; i < offset + length; i++) {
            ret += array[i].position();
        }
        return ret;
    }

    /**
     * Copies as many bytes as possible from {@code from} into the combined buffer. Stops
     * when either {@code from} is exhausted or the combined buffer is full.
     *
     * @return the number of bytes copied
     *
     * @throws ReadOnlyBufferException if this buffer is read-only
     */
    public int putRemaining(ByteBuffer from) {
        if (isReadOnly()) {
            throw new ReadOnlyBufferException();
        }
        int totalBytes = 0;
        for (int i = offset; i < offset + length; i++) {
            if (!from.hasRemaining()) {
                break;
            }
            ByteBuffer dstBuffer = array[i];
            int bytes = Math.min(from.remaining(), dstBuffer.remaining());
            ByteBufferUtil.copy(from, dstBuffer, bytes);
            totalBytes += bytes;
        }
        return totalBytes;
    }

    /**
     * Copies exactly {@code length} bytes from {@code from} into the combined buffer.
     *
     * @throws IllegalArgumentException if {@code from} has fewer than {@code length} bytes
     *                                  remaining, or the combined buffer have fewer than
     *                                  {@code length} bytes of remaining capacity
     * @throws ReadOnlyBufferException if this buffer is read-only
     */
    public void put(ByteBuffer from, int length) {
        if (isReadOnly()) {
            throw new ReadOnlyBufferException();
        }
        if (from.remaining() < length) {
            throw new IllegalArgumentException();
        }
        if (remaining() < length) {
            throw new IllegalArgumentException();
        }
        int totalBytes = 0;
        for (int i = offset; i < offset + this.length; i++) {
            int pending = length - totalBytes;
            if (pending == 0) {
                break;
            }
            int bytes = Math.min(pending, array[i].remaining());
            ByteBuffer dstBuffer = array[i];
            ByteBufferUtil.copy(from, dstBuffer, bytes);
            totalBytes += bytes;
        }
    }

    /**
     * Copies as many bytes as possible from the combined buffer into {@code dst}. Stops
     * when either {@code dst} is full or the combined buffer is exhausted.
     *
     * @return the number of bytes copied
     */
    public int getRemaining(ByteBuffer dst) {
        int totalBytes = 0;
        for (int i = offset; i < offset + length; i++) {
            if (!dst.hasRemaining()) {
                break;
            }
            ByteBuffer srcBuffer = array[i];
            int bytes = Math.min(dst.remaining(), srcBuffer.remaining());
            ByteBufferUtil.copy(srcBuffer, dst, bytes);
            totalBytes += bytes;
        }
        return totalBytes;
    }

    /**
     * Copies exactly {@code length} bytes from the combined buffer into {@code dst}.
     *
     * @throws IllegalArgumentException if the combined buffer has fewer than {@code length} bytes
     *                                  remaining, or {@code dst} has fewer than {@code length}
     *                                  bytes of remaining capacity
     */
    public void get(ByteBuffer dst, int length) {
        if (remaining() < length) {
            throw new IllegalArgumentException();
        }
        if (dst.remaining() < length) {
            throw new IllegalArgumentException();
        }
        int totalBytes = 0;
        for (int i = offset; i < offset + this.length; i++) {
            int pending = length - totalBytes;
            if (pending == 0) {
                break;
            }
            ByteBuffer srcBuffer = array[i];
            int bytes = Math.min(pending, srcBuffer.remaining());
            ByteBufferUtil.copy(srcBuffer, dst, bytes);
            totalBytes += bytes;
        }
    }

    /**
     * Returns {@code true} if the combined buffer has bytes remaining.
     */
    public boolean hasRemaining() {
        return remaining() > 0;
    }

    /**
     * Returns {@code true} if any active buffer is read-only, making the combined buffer
     * effectively read-only too.
     */
    public boolean isReadOnly() {
        for (int i = offset; i < offset + length; i++) {
            if (array[i].isReadOnly()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "ByteBufferSet[" + Arrays.toString(array) + ":" + offset + ":" + length + "]";
    }
}
