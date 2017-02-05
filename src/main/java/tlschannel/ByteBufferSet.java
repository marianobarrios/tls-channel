package tlschannel;

import java.nio.ByteBuffer;
import java.util.Arrays;

public class ByteBufferSet {

	public final ByteBuffer[] array;
	public final int offset;
	public final int length;

	public ByteBufferSet(ByteBuffer[] array, int offset, int length) {
		if (array == null)
			throw new NullPointerException();
		if (array.length < offset) 
			throw new IllegalArgumentException();
		if (array.length < offset + length) 
			throw new IllegalArgumentException();
		for (int i = offset; i < offset + length; i++) {
			if (array[i] == null)
				throw new NullPointerException();
		}
		this.array = array;
		this.offset = offset;
		this.length = length;
	}

	public ByteBufferSet(ByteBuffer[] array) {
		this(array, 0, array.length);
	}

	public ByteBufferSet(ByteBuffer buffer) {
		this(new ByteBuffer[] { buffer });
	}

	public long remaining() {
		long ret = 0;
		for (int i = offset; i < offset + length; i++) {
			ret += array[i].remaining();
		}
		return ret;
	}

	public int putRemaining(ByteBuffer from) {
		int totalBytes = 0;
		for (int i = offset; i < offset + length; i++) {
			if (!from.hasRemaining())
				break;
			ByteBuffer dstBuffer = array[i];
			int bytes = Math.min(from.remaining(), dstBuffer.remaining());
			ByteBuffer tmp = from.duplicate();
			tmp.limit(from.position() + bytes);
			dstBuffer.put(tmp);
			from.position(from.position() + bytes);
			totalBytes += bytes;
		}
		return totalBytes;
	}


	public ByteBufferSet put(ByteBuffer from, int length) {
		if (remaining() < length) {
			throw new IllegalArgumentException();
		}
		int totalBytes = 0;
		for (int i = offset; i < offset + this.length; i++) {
			int pending = length - totalBytes;
			int bytes = Math.min(pending, (int) remaining());
			ByteBuffer dstBuffer = array[i];
			ByteBuffer tmp = from.duplicate();
			tmp.limit(from.position() + bytes);
			dstBuffer.put(tmp);
			from.position(from.position() + bytes);
			totalBytes += bytes;
		}
		return this;
	}

	public int getRemaining(ByteBuffer dst) {
		int totalBytes = 0;
		for (int i = offset; i < offset + length; i++) {
			if (!dst.hasRemaining())
				break;
			ByteBuffer srcBuffer = array[i];
			int bytes = Math.min(dst.remaining(), srcBuffer.remaining());
			ByteBuffer tmp = srcBuffer.duplicate();
			tmp.limit(srcBuffer.position() + bytes);
			dst.put(tmp);
			srcBuffer.position(srcBuffer.position() + bytes);
			totalBytes += bytes;
		}
		return totalBytes;
	}
	
	public ByteBufferSet get(ByteBuffer dst, int length) {
		if (dst.remaining() < length) {
			throw new IllegalArgumentException();
		}
		int totalBytes = 0;
		for (int i = offset; i < offset + this.length; i++) {
			int pending = length - totalBytes;
			ByteBuffer srcBuffer = array[i];
			int bytes = Math.min(pending, srcBuffer.remaining());
			ByteBuffer tmp = srcBuffer.duplicate();
			tmp.limit(srcBuffer.position() + bytes);
			dst.put(tmp);
			srcBuffer.position(srcBuffer.position() + bytes);
			totalBytes += bytes;
		}
		return this;
	}

	public boolean hasRemaining() {
		return remaining() > 0;
	}

	public boolean isReadOnly() {
		for (int i = offset; i < offset + length; i++) {
			if (array[i].isReadOnly())
				return true;
		}
		return false;
	}

	@Override
	public String toString() {
		return "ByteBufferSet[array=" + Arrays.toString(array) + ", offset=" + offset + ", length=" + length + "]";
	}

}
