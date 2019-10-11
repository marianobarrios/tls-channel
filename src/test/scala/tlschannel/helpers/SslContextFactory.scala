package tlschannel.helpers

import javax.net.ssl.SSLContext
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore
import java.security.Security

import com.typesafe.scalalogging.StrictLogging

class SslContextFactory(val protocol: String = "TLSv1.2") extends StrictLogging {

  /*
   * Overrule paternalistic JVM behavior of forbidding ciphers even if allowed in code.
   */
  Security.setProperty("jdk.tls.disabledAlgorithms", "")

  val authenticatedContext = {
    val sslContext = SSLContext.getInstance(protocol)
    val ks = KeyStore.getInstance("JKS");
    for (keystoreFile <- resource.managed(getClass.getClassLoader.getResourceAsStream("keystore.jks"))) {
      ks.load(keystoreFile, "password".toCharArray())
      val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      tmf.init(ks)
      val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
      kmf.init(ks, "password".toCharArray())
      sslContext.init(kmf.getKeyManagers, tmf.getTrustManagers, null)
    }
    sslContext
  }
  
  val anonContext = {
    val ctx = SSLContext.getInstance(protocol)
    ctx.init(null, null, null)
    ctx
  }
  
  val authenticatedCiphers = ciphers(authenticatedContext).filterNot(_.contains("_anon_"))
  val anonCiphers = ciphers(anonContext).filter(_.contains("_anon_"))

  val allCiphers = {
    val ret = authenticatedCiphers.map((_, authenticatedContext)) ++ anonCiphers.map((_, anonContext))
    ret.sortBy(_._1)
  }
  
  val standardCipher = ("TLS_DH_anon_WITH_AES_128_CBC_SHA", anonContext)
  
  private def ciphers(ctx: SSLContext): Seq[String] = {
    ctx.createSSLEngine().getSupportedCipherSuites

      // this is not a real cipher, but a hack actually
      .filterNot(_ == "TLS_EMPTY_RENEGOTIATION_INFO_SCSV")

      // disable problematic ciphers
      .filterNot{c =>
        Seq(
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
          "SSL_RSA_EXPORT_WITH_RC4_40_MD5"
        ).contains(c)
      }

      // No SHA-2 with TLS < 1.2
      .filterNot { c =>
        !Set("TLSv1.2", "TLSv1.3").contains(protocol) && (c.endsWith("_SHA256") || c.endsWith("_SHA384"))
      }

      // Disable cipher only supported in TLS > 1.3
      .filterNot { c =>
        protocol != "TLSv1.3" &&
          Set(
            "TLS_AES_128_GCM_SHA256",
            "TLS_AES_256_GCM_SHA384",
            "TLS_CHACHA20_POLY1305_SHA256"
          ).contains(c)
      }

      // https://bugs.openjdk.java.net/browse/JDK-8224997
      .filterNot { c =>
        Set(
          "TLS_DHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
          "TLS_ECDHE_ECDSA_WITH_CHACHA20_POLY1305_SHA256",
          "TLS_ECDHE_RSA_WITH_CHACHA20_POLY1305_SHA256",
        ).contains(c)
      }
  }
  
}

object SslContextFactory {

  val tlsMaxDataSize = math.pow(2, 14).toInt
  val certificateCommonName = "name" // must match what's in the certificates

}