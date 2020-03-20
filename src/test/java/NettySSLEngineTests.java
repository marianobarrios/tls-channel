import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.SimpleTrustManagerFactory;
import org.junit.jupiter.api.Test;
import tlschannel.ClientTlsChannel;
import tlschannel.HeapBufferAllocator;
import tlschannel.NeedsWriteException;

import javax.net.ssl.*;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

public class NettySSLEngineTests {

    @Test
    public void nettySSLEngineInitializationTest() throws IOException {
        SslContext ctx = SslContextBuilder.forClient()
                .sslProvider(SslProvider.OPENSSL)
                .trustManager(dummyTrustManagerFacotory)
                .protocols("TLSv1.3")
                .build();
        SSLEngine sslEngine = ctx.newEngine(ByteBufAllocator.DEFAULT, "test", 0);

        ClientTlsChannel chan = ClientTlsChannel.newBuilder(new DummyByteChannel(), sslEngine)
                .withEncryptedBufferAllocator(new HeapBufferAllocator())
                .build();

        try {
            chan.handshake();
        }
        catch (NeedsWriteException es) {
            // test passed, expected exception
        }
    }

    private static class DummyByteChannel implements ByteChannel {
        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        public void close() throws IOException {
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            return 0;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return 0;
        }
    }

    private TrustManagerFactory dummyTrustManagerFacotory = new SimpleTrustManagerFactory() {
        private TrustManager[] trustManagers = new TrustManager[] {new DummyTrustManager()};

        @Override
        protected void engineInit(KeyStore keyStore) throws Exception {
        }

        @Override
        protected void engineInit(ManagerFactoryParameters managerFactoryParameters) throws Exception {
        }

        @Override
        protected TrustManager[] engineGetTrustManagers() {
            return trustManagers;
        }
    };

    private static class DummyTrustManager extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s, Socket socket) throws CertificateException {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s, Socket socket) throws CertificateException {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] x509Certificates, String s, SSLEngine sslEngine) throws CertificateException {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] x509Certificates, String s, SSLEngine sslEngine) throws CertificateException {
        }
    }
}
