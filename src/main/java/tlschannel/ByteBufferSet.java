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
