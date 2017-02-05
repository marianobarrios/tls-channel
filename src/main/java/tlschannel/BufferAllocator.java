package tlschannel;

import java.nio.ByteBuffer;

/**
 * A factory for {@link ByteBuffer}s. Implementations are free to return heap or direct 
 * buffers, or to do any kind of pooling.
 */
public interface BufferAllocator {

	ByteBuffer allocate(int size);

	void free(ByteBuffer buffer);
	
}
