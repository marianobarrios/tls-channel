package tlschannel.helpers;

import java.io.IOException;
import java.io.InputStream;
import java.security.*;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public class SslContextFactory {

    public static final int tlsMaxDataSize = (int) Math.pow(2, 14);
    public static final String certificateCommonName = "name"; // must match what's in the certificates

    private final String protocol;
    private final SSLContext defaultContext;
    private final List<String> allCiphers;

    public SslContextFactory(String protocol) {
        this.protocol = protocol;
        try {
            SSLContext sslContext = SSLContext.getInstance(protocol);
            KeyStore ks = KeyStore.getInstance("JKS");
            try (InputStream keystoreFile = getClass().getClassLoader().getResourceAsStream("keystore.jks")) {
                ks.load(keystoreFile, "password".toCharArray());
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(ks);
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, "password".toCharArray());
                sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
            }
            this.defaultContext = sslContext;
        } catch (IOException | GeneralSecurityException e) {
            throw new RuntimeException(e);
        }
        this.allCiphers = ciphers(defaultContext).stream().sorted().collect(Collectors.toList());
    }

    public SslContextFactory() {
        this("TLSv1.2");
    }

    private List<String> ciphers(SSLContext ctx) {
        return Arrays.stream(ctx.createSSLEngine().getSupportedCipherSuites())
                // this is not a real cipher, but a hack actually
                .filter(c -> !Objects.equals(c, "TLS_EMPTY_RENEGOTIATION_INFO_SCSV"))
                // disable problematic ciphers
                .filter(c -> !Arrays.asList(
                                "TLS_KRB5_EXPORT_WITH_DES_CBC_40_MD5",
                                "TLS_KRB5_EXPORT_WITH_DES_CBC_40_SHA",
                                "TLS_KRB5_EXPORT_WITH_RC4_40_MD5",
                                "TLS_KRB5_EXPORT_WITH_RC4_40_SHA",
                                "TLS_KRB5_WITH_3DES_EDE_CBC_MD5",
                                "TLS_KRB5_WITH_3DES_EDE_CBC_SHA",
                                "TLS_KRB5_WITH_DES_CBC_MD5",
                                "TLS_KRB5_WITH_DES_CBC_SHA",
                                "TLS_KRB5_WITH_RC4_128_MD5",
                                "TLS_KRB5_WITH_RC4_128_SHA",
                                "SSL_RSA_EXPORT_WITH_DES40_CBC_SHA",
                                "SSL_RSA_EXPORT_WITH_RC4_40_MD5")
                        .contains(c))
                // No SHA-2 with TLS < 1.2
                .filter(c -> Arrays.asList("TLSv1.2", "TLSv1.3").contains(protocol)
                        || !c.endsWith("_SHA256") && !c.endsWith("_SHA384"))
                // Disable cipher only supported in TLS >= 1.3
                .filter(c -> protocol.compareTo("TLSv1.3") > 0
                        || !Arrays.asList("TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384")
                                .contains(c))
                // https://bugs.openjdk.java.net/browse/JDK-8224997
                .filter(c -> !c.endsWith("_CHACHA20_POLY1305_SHA256"))
                // Anonymous ciphers are problematic because they are disabled in some VMs
                .filter(c -> !c.contains("_anon_"))
                .collect(Collectors.toList());
    }

    public SSLContext defaultContext() {
        return defaultContext;
    }

    public List<String> getAllCiphers() {
        return allCiphers;
    }
}
