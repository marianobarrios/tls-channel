package tlschannel;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.LongAdder;

/**
 * Allocator that creates heap buffers. The {@link #free(ByteBuffer)} method is a
 * no-op, as heap buffer are handled completely by the garbage collector.
 * 
 * Direct buffers are generally used as a simple and generally good enough
 * default solution.
 */
public class HeapBufferAllocator implements BufferAllocator {

	private LongAdder bytesAllocated = new LongAdder();
	private LongAdder bytesDeallocated = new LongAdder();

	@Override
	public ByteBuffer allocate(int size) {
		ByteBuffer buf = ByteBuffer.allocate(size);
		bytesAllocated.add(size);
		return buf;
	}

	@Override
	public void free(ByteBuffer buffer) {
		// GC does it
		bytesDeallocated.add(buffer.capacity());
	}

	@Override
	public long bytesAllocated() {
		return bytesAllocated.longValue();
	}

	@Override
	public long bytesDeallocated() {
		return bytesDeallocated.longValue();
	}

}
