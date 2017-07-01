package tlschannel.util;

import javax.net.ssl.SSLEngineResult;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Util {

	private static final Logger logger = LoggerFactory.getLogger(Util.class);

	public static void assertTrue(boolean condition) {
		if (!condition)
			throw new AssertionError();
	}

	/**
	 * Convert a {@link SSLEngineResult} into a {@link String}, this is needed
	 * because the supplied method includes a log-breaking newline.
	 */
	public static String resultToString(SSLEngineResult result) {
		return String.format("status=%s,handshakeStatus=%s,bytesConsumed=%d,bytesConsumed=%d", result.getStatus(),
				result.getHandshakeStatus(), result.bytesProduced(), result.bytesConsumed());
	}

}
