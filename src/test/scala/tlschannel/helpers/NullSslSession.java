package tlschannel.helpers;

import java.security.Principal;
import java.security.cert.Certificate;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.security.cert.X509Certificate;

public class NullSslSession implements SSLSession {

    private final int bufferSize;

    public NullSslSession(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    @Override
    public byte[] getId() {
        return new byte[0];
    }

    @Override
    public SSLSessionContext getSessionContext() {
        return null;
    }

    @Override
    public long getCreationTime() {
        return System.currentTimeMillis();
    }

    @Override
    public long getLastAccessedTime() {
        return System.currentTimeMillis();
    }

    @Override
    public void invalidate() {}

    @Override
    public boolean isValid() {
        return false;
    }

    @Override
    public void putValue(String name, Object value) {}

    @Override
    public Object getValue(String name) {
        return null;
    }

    @Override
    public void removeValue(String name) {}

    @Override
    public String[] getValueNames() {
        return new String[0];
    }

    @Override
    public Certificate[] getPeerCertificates() {
        return new Certificate[0];
    }

    @Override
    public Certificate[] getLocalCertificates() {
        return new Certificate[0];
    }

    @Override
    public Principal getPeerPrincipal() {
        return null;
    }

    @Override
    public Principal getLocalPrincipal() {
        return null;
    }

    @Override
    public String getCipherSuite() {
        return null;
    }

    @Override
    public String getProtocol() {
        return "";
    }

    @Override
    public String getPeerHost() {
        return null;
    }

    @Override
    public int getPeerPort() {
        return 0;
    }

    @Override
    public int getPacketBufferSize() {
        return 0;
    }

    @Override
    public int getApplicationBufferSize() {
        return bufferSize;
    }

    @Override
    @SuppressWarnings("removal")
    public X509Certificate[] getPeerCertificateChain() {
        return new X509Certificate[0];
    }
}
