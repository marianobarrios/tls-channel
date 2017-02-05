package tlschannel;

import java.nio.ByteBuffer;

import engine.misc.DeallocationHelper;

/*
 * Simple allocator that just creates direct buffers. The {@link #free(ByteBuffer} 
 * method, if called, deallocated the buffer immediately, without having to wait 
 * for GC (and the finalizer) to run.
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
