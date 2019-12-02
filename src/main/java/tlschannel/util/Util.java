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
	 *
	 * @param result the SSLEngineResult
	 * @return the resulting string
	 */
	public static String resultToString(SSLEngineResult result) {
		return String.format("status=%s,handshakeStatus=%s,bytesConsumed=%d,bytesConsumed=%d", result.getStatus(),
				result.getHandshakeStatus(), result.bytesProduced(), result.bytesConsumed());
	}

	public static int getJavaMajorVersion() {
		String version = System.getProperty("java.version");
		if (version.startsWith("1.")) {
			version = version.substring(2, 3);
		} else {
			int dot = version.indexOf(".");
			if (dot != -1) {
				version = version.substring(0, dot);
			}
		}
		return Integer.parseInt(version);
	}

}
