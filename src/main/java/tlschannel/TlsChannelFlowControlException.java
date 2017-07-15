package tlschannel;

import java.io.IOException;
import java.nio.channels.ByteChannel;

/**
 * Base class for exceptions used to control flow.
 * <p>
 * Because exceptions of this class are not used to signal errors, they don't
 * contain stack traces, to improve efficiency.
 * <p>
 * This class inherits from {@link IOException} as a compromise to allow
 * {@link TlsChannel} to throw it while still implementing the
 * {@link ByteChannel} interface.
 */
public abstract class TlsChannelFlowControlException extends IOException {

    public TlsChannelFlowControlException() {
        super();
    }

    /**
     * For efficiency, override this method to do nothing.
     */
    @Override
    public Throwable fillInStackTrace() {
        return this;
    }

}
