package tlschannel;

import java.nio.ByteBuffer;

import engine.misc.DeallocationHelper;

/**
 * Allocator that creates direct buffers. The {@link #free(ByteBuffer} method,
 * if called, deallocates the buffer immediately, without having to wait for GC
 * (and the finalizer) to run. Calling {@link #free(ByteBuffer} is actually
 * optional, but should result in reduced memory consumption.
 * 
 * Direct buffers are generally preferred for using with I/O, to avoid an extra
 * user-space copy, or to reduce garbage collection overhead.
 */
public class DirectBufferAllocator implements BufferAllocator {

	private DeallocationHelper deallocationHelper = new DeallocationHelper();

	@Override
	public ByteBuffer allocate(int size) {
		return ByteBuffer.allocateDirect(size);
	}

	@Override
	public void free(ByteBuffer buffer) {
		// do not wait for GC (and finalizer) to run
		deallocationHelper.deallocate(buffer);
	}

}
