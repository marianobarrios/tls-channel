package tlschannel;

import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public class ContextFactory {

  public static SSLContext authenticatedContext(String protocol)
      throws GeneralSecurityException, IOException {
    SSLContext sslContext = SSLContext.getInstance(protocol);
    KeyStore ks = KeyStore.getInstance("PKCS12");
    try (InputStream keystoreFile =
        SimpleBlockingServer.class.getClassLoader().getResourceAsStream("keystore.p12")) {
      ks.load(keystoreFile, "password".toCharArray());
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(ks);
      KeyManagerFactory kmf =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(ks, "password".toCharArray());
      sslContext.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
      return sslContext;
    }
  }
}
