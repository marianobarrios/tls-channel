package tlschannel;

import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.SimpleTrustManagerFactory;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import javax.net.ssl.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

@TestInstance(Lifecycle.PER_CLASS)
public class NettySslEngineTest {

    // dummy handshake as minimal sanity test
    @Test
    void testDummyHandshake() throws SSLException {
        io.netty.handler.ssl.SslContext ctx = SslContextBuilder.forClient()
                .sslProvider(SslProvider.OPENSSL)
                .trustManager(dummyTrustManagerFactory)
                .protocols("TLSv1.3")
                .build();
        SSLEngine sslEngine = ctx.newEngine(ByteBufAllocator.DEFAULT, "test", 0);

        ClientTlsChannel channel = ClientTlsChannel.newBuilder(new DummyByteChannel(), sslEngine)
                .withEncryptedBufferAllocator(new HeapBufferAllocator())
                .build();

        assertThrows(NeedsWriteException.class, () -> channel.handshake());
    }

    private final TrustManagerFactory dummyTrustManagerFactory = new SimpleTrustManagerFactory() {
        @Override
        public void engineInit(KeyStore keyStore) {}

        @Override
        public void engineInit(ManagerFactoryParameters params) {}

        @Override
        public TrustManager[] engineGetTrustManagers() {
            return new TrustManager[] {new DummyTrustManager()};
        }
    };

    private static class DummyByteChannel implements ByteChannel {
        @Override
        public boolean isOpen() {
            return false;
        }

        @Override
        public void close() {}

        @Override
        public int write(ByteBuffer src) {
            return 0;
        }

        @Override
        public int read(ByteBuffer dst) {
            return 0;
        }
    }

    private static class DummyTrustManager extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] certs, String s) {}

        @Override
        public void checkServerTrusted(X509Certificate[] certs, String s) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }

        @Override
        public void checkClientTrusted(X509Certificate[] certs, String s, Socket socket) {}

        @Override
        public void checkServerTrusted(X509Certificate[] certs, String s, Socket socket) {}

        @Override
        public void checkClientTrusted(X509Certificate[] certs, String s, SSLEngine sslEngine) {}

        @Override
        public void checkServerTrusted(X509Certificate[] certs, String s, SSLEngine sslEngine) {}
    }
}
