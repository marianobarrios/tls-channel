package tlschannel;

import java.nio.ByteBuffer;

/*
 * Simple allocator that just creates heap buffers.
 */
public class HeapBufferAllocator implements BufferAllocator {

	@Override
	public ByteBuffer allocate(int size) {
		return ByteBuffer.allocate(size);
	}

	@Override
	public void free(ByteBuffer buffer) {
		// GC does it
	}

}
