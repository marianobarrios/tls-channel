package tlschannel;

import java.io.IOException;

/**
 * Base class for exceptions used to control flow (instead of signaling errors).
 */
public abstract class TlsChannelFlowControlException extends IOException {

	/**
	 * For efficiency, override this method to do nothing.
	 */
	@Override
	public Throwable fillInStackTrace() {
		return this;
	}

}
