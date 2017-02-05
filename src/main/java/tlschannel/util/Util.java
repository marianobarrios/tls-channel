package tlschannel.util;

import java.nio.ByteBuffer;
import java.nio.channels.Channel;

import javax.net.ssl.SSLEngineResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tlschannel.BufferAllocator;

public class Util {

	private static final Logger logger = LoggerFactory.getLogger(Util.class);
	
	public static void closeChannel(Channel channel) {
		try {
			channel.close();
		} catch (Exception e) {
			// pass
		}
	}
	
	public static void assertTrue(boolean condition) {
		if (!condition)
			throw new AssertionError();
	}

	public static ByteBuffer enlarge(BufferAllocator allocator, ByteBuffer buffer, String name, int maxSize) {
		if (buffer.capacity() >= maxSize) {
			throw new IllegalStateException(
					String.format("%s buffer insufficient despite having capacity of %d", name, buffer.capacity()));
		}
		int newCapacity = Math.min(buffer.capacity() * 2, maxSize);
		logger.trace("{} buffer too small, increasing from {} to {}", name, buffer.capacity(), newCapacity);
		return resize(allocator, buffer, newCapacity);
	}

	public static ByteBuffer resize(BufferAllocator allocator, ByteBuffer buffer, int newCapacity) {
		ByteBuffer newBuffer = allocator.allocate(newCapacity);
		buffer.flip();
		newBuffer.put(buffer);
		allocator.free(buffer);
		return newBuffer;
	}

	/**
	 * Convert a {@link SSLEngineResult} into a {@link String}, this is needed
	 * because the supplied method includes a log-breaking newline.
	 */
	public static String resultToString(SSLEngineResult result) {
		return String.format("status=%s,handshakeStatus=%s,bytesProduced=%d,bytesConsumed=%d", result.getStatus(),
				result.getHandshakeStatus(), result.bytesProduced(), result.bytesConsumed());
	}


}
