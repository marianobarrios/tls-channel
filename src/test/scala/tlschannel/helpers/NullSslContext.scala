package tlschannel.helpers

import javax.net.ssl.SSLContext
import javax.net.ssl.SSLContextSpi
import javax.net.ssl.KeyManager
import javax.net.ssl.TrustManager
import java.security.SecureRandom

class NullSslContextSpi extends SSLContextSpi {
  override def engineCreateSSLEngine() = new NullSslEngine
  def engineCreateSSLEngine(x$1: String, x$2: Int) = throw new UnsupportedOperationException
  def engineGetClientSessionContext() = throw new UnsupportedOperationException
  def engineGetServerSessionContext() = throw new UnsupportedOperationException
  def engineGetServerSocketFactory() = throw new UnsupportedOperationException
  def engineGetSocketFactory() = throw new UnsupportedOperationException
  def engineInit(x$1: Array[KeyManager], x$2: Array[TrustManager], x$3: SecureRandom): Unit =
    throw new UnsupportedOperationException
}

class NullSslContext extends SSLContext(new NullSslContextSpi, null, null)
