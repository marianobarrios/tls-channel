package tlschannel.util;

import javax.net.ssl.SSLEngineResult;
import java.util.logging.Logger;

public class Util {

    private static final Logger logger = Logger.getLogger(Util.class.getName());

    public static void assertTrue(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    /**
     * Convert a {@link SSLEngineResult} into a {@link String}, this is needed because the supplied
     * method includes a log-breaking newline.
     *
     * @param result the SSLEngineResult
     * @return the resulting string
     */
    public static String resultToString(SSLEngineResult result) {
        return String.format(
                "status=%s,handshakeStatus=%s,bytesProduced=%d,bytesConsumed=%d",
                result.getStatus(), result.getHandshakeStatus(), result.bytesProduced(), result.bytesConsumed());
    }

    public static int getJavaMajorVersion() {
        String version = System.getProperty("java.version");
        if (version.startsWith("1.")) {
            version = version.substring(2);
        }
        // Allow these formats:
        // 1.8.0_72-ea
        // 9-ea
        // 9
        // 9.0.1
        // 17
        int dotPos = version.indexOf('.');
        int dashPos = version.indexOf('-');
        return Integer.parseInt(version.substring(0, dotPos > -1 ? dotPos : dashPos > -1 ? dashPos : version.length()));
    }
}
