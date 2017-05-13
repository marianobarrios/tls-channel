package tlschannel.util;

import java.nio.ByteBuffer;

import javax.net.ssl.SSLEngineResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tlschannel.BufferAllocator;
import tlschannel.impl.TlsChannelImpl;

public class Util {

	private static final Logger logger = LoggerFactory.getLogger(Util.class);

	public static void assertTrue(boolean condition) {
		if (!condition)
			throw new AssertionError();
	}

	public static ByteBuffer enlarge(BufferAllocator allocator, ByteBuffer buffer, String name, int maxSize,
			boolean zero) {
		if (buffer.capacity() >= maxSize) {
			throw new IllegalStateException(
					String.format("%s buffer insufficient despite having capacity of %d", name, buffer.capacity()));
		}
		int newCapacity = Math.min(buffer.capacity() * 2, maxSize);
		logger.trace("{} buffer too small, increasing from {} to {}", name, buffer.capacity(), newCapacity);
		return resize(allocator, buffer, newCapacity, zero);
	}

	public static ByteBuffer resize(BufferAllocator allocator, ByteBuffer buffer, int newCapacity, boolean zero) {
		ByteBuffer newBuffer = allocator.allocate(newCapacity);
		buffer.flip();
		newBuffer.put(buffer);
		if (zero) {
			buffer.clear();
			zeroRemaining(buffer);
		}
		allocator.free(buffer);
		return newBuffer;
	}

	private final static byte[] zeros = new byte[TlsChannelImpl.maxTlsPacketSize];

	/**
	 * Fill with zeros the remaining of the supplied buffer. This method does
	 * not change the buffer position.
	 * 
	 * Typically used for security reasons, with buffers that contains
	 * now-unused plaintext.
	 */
	public static void zeroRemaining(ByteBuffer buffer) {
		buffer.mark();
		buffer.put(zeros, 0, buffer.remaining());
		buffer.reset();
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
