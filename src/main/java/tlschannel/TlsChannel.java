package tlschannel;

import java.io.IOException;
import java.nio.channels.ByteChannel;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.ScatteringByteChannel;

import javax.net.ssl.SSLSession;

public interface TlsChannel extends ByteChannel, GatheringByteChannel, ScatteringByteChannel {

	/**
	 * Return a reference to the underlying {@link ByteChannel}.
	 */
	ByteChannel getUnderlying();

	void renegotiate() throws IOException;

	void negotiate() throws IOException;

	void close();

	boolean isOpen();

	SSLSession getSession();

	boolean getRunTasks();
	
}
