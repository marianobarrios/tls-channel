package tlschannel.helpers;

import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLSession;
import tlschannel.impl.ByteBufferSet;
import tlschannel.impl.ByteBufferUtil;

/**
 * "Null" {@link SSLEngine} that does nothing to the bytesProduced.
 */
class NullSslEngine extends SSLEngine {

    /** Internal buffers are still used to prevent any underlying optimization of the wrap/unwrap.
     */
    private final int bufferSize = 16000;

    @Override
    public void beginHandshake() {}

    @Override
    public void closeInbound() {}

    @Override
    public void closeOutbound() {}

    @Override
    public Runnable getDelegatedTask() {
        return null;
    }

    @Override
    public boolean getEnableSessionCreation() {
        return true;
    }

    @Override
    public String[] getEnabledCipherSuites() {
        return new String[] {};
    }

    @Override
    public String[] getEnabledProtocols() {
        return new String[] {};
    }

    @Override
    public HandshakeStatus getHandshakeStatus() {
        return HandshakeStatus.NOT_HANDSHAKING;
    }

    @Override
    public boolean getNeedClientAuth() {
        return false;
    }

    @Override
    public SSLSession getSession() {
        return new NullSslSession(bufferSize);
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return new String[] {};
    }

    @Override
    public String[] getSupportedProtocols() {
        return new String[] {};
    }

    @Override
    public boolean getUseClientMode() {
        return true;
    }

    @Override
    public boolean getWantClientAuth() {
        return false;
    }

    @Override
    public boolean isInboundDone() {
        return false;
    }

    @Override
    public boolean isOutboundDone() {
        return false;
    }

    @Override
    public void setEnableSessionCreation(boolean b) {}

    @Override
    public void setEnabledCipherSuites(String[] a) {}

    @Override
    public void setEnabledProtocols(String[] a) {}

    @Override
    public void setNeedClientAuth(boolean b) {}

    @Override
    public void setUseClientMode(boolean b) {}

    @Override
    public void setWantClientAuth(boolean b) {}

    private final ByteBuffer unwrapBuffer = ByteBuffer.allocate(bufferSize);
    private final ByteBuffer wrapBuffer = ByteBuffer.allocate(bufferSize);

    @Override
    public SSLEngineResult unwrap(ByteBuffer src, ByteBuffer[] dsts, int offset, int length) {
        ByteBufferSet dstSet = new ByteBufferSet(dsts, offset, length);
        if (!src.hasRemaining()) {
            return new SSLEngineResult(Status.BUFFER_UNDERFLOW, HandshakeStatus.NOT_HANDSHAKING, 0, 0);
        }
        int unwrapSize = Math.min(unwrapBuffer.capacity(), src.remaining());
        if (dstSet.remaining() < unwrapSize) {
            return new SSLEngineResult(Status.BUFFER_OVERFLOW, HandshakeStatus.NOT_HANDSHAKING, 0, 0);
        }
        unwrapBuffer.clear();
        ByteBufferUtil.copy(src, unwrapBuffer, unwrapSize);
        unwrapBuffer.flip();
        dstSet.putRemaining(unwrapBuffer);
        return new SSLEngineResult(Status.OK, HandshakeStatus.NOT_HANDSHAKING, unwrapSize, unwrapSize);
    }

    @Override
    public SSLEngineResult wrap(ByteBuffer[] srcs, int offset, int length, ByteBuffer dst) {
        ByteBufferSet srcSet = new ByteBufferSet(srcs, offset, length);
        if (!srcSet.hasRemaining()) {
            return new SSLEngineResult(Status.OK, HandshakeStatus.NOT_HANDSHAKING, 0, 0);
        }
        int wrapSize = (int) Math.min(wrapBuffer.capacity(), srcSet.remaining());
        if (dst.remaining() < wrapSize) {
            return new SSLEngineResult(Status.BUFFER_OVERFLOW, HandshakeStatus.NOT_HANDSHAKING, 0, 0);
        }
        wrapBuffer.clear();
        srcSet.get(wrapBuffer, wrapSize);
        wrapBuffer.flip();
        dst.put(wrapBuffer);
        return new SSLEngineResult(Status.OK, HandshakeStatus.NOT_HANDSHAKING, wrapSize, wrapSize);
    }
}
