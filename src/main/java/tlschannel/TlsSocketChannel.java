package tlschannel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;

import javax.net.ssl.SSLSession;

public interface TlsSocketChannel extends ByteChannel {

	ByteChannel getWrapped();

	int read(ByteBuffer dstBuffer) throws IOException;

	int write(ByteBuffer srcBuffer) throws IOException;

	void renegotiate() throws IOException;

	void doPassiveHandshake() throws IOException;

	void doHandshake() throws IOException;

	void close();

	boolean isOpen();

	SSLSession getSession();

}
