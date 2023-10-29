package tlschannel.helpers;

import javax.net.ssl.*;
import java.security.SecureRandom;


public class NullSslContext extends SSLContext {
  public NullSslContext() {
    super(new NullSslContextSpi(), null, null);
  }

}

class NullSslContextSpi extends SSLContextSpi {

  @Override
  public SSLEngine engineCreateSSLEngine() {
    return new NullSslEngine();
  }

  @Override
  public SSLEngine engineCreateSSLEngine(String s, int i) {
    throw new UnsupportedOperationException();
  }

  @Override
  public SSLSessionContext engineGetClientSessionContext() {
    throw new UnsupportedOperationException();
  }

  @Override
  public SSLSessionContext engineGetServerSessionContext() {
    throw new UnsupportedOperationException();
  }
  @Override
  public SSLServerSocketFactory engineGetServerSocketFactory() {
    throw new UnsupportedOperationException();
  }

  @Override
  public SSLSocketFactory engineGetSocketFactory() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void engineInit(KeyManager[] km, TrustManager[] tm, SecureRandom sc) {
    throw new UnsupportedOperationException();
  }

}