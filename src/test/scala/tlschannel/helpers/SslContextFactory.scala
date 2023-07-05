package tlschannel.helpers

import javax.net.ssl.SSLContext
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore

import scala.util.Using

class SslContextFactory(val protocol: String = "TLSv1.2") {

  val defaultContext = {
    val sslContext = SSLContext.getInstance(protocol)
    val ks = KeyStore.getInstance("JKS")
    Using.resource(getClass.getClassLoader.getResourceAsStream("keystore.jks")) { keystoreFile =>
      ks.load(keystoreFile, "password".toCharArray())
      val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
      tmf.init(ks)
      val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
      kmf.init(ks, "password".toCharArray())
      sslContext.init(kmf.getKeyManagers, tmf.getTrustManagers, null)
    }
    sslContext
  }

  val allCiphers = {
    ciphers(defaultContext).sorted
  }

  private def ciphers(ctx: SSLContext): Seq[String] = {
    ctx
      .createSSLEngine()
      .getSupportedCipherSuites
      .toSeq
      // this is not a real cipher, but a hack actually
      .filterNot(_ == "TLS_EMPTY_RENEGOTIATION_INFO_SCSV")
      // disable problematic ciphers
      .filterNot { c =>
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
      // Disable cipher only supported in TLS >= 1.3
      .filterNot { c =>
        protocol < "TLSv1.3" &&
        Set(
          "TLS_AES_128_GCM_SHA256",
          "TLS_AES_256_GCM_SHA384"
        ).contains(c)
      }
      // https://bugs.openjdk.java.net/browse/JDK-8224997
      .filterNot(_.endsWith("_CHACHA20_POLY1305_SHA256"))
      // Anonymous ciphers are problematic because the are disabled in some VMs
      .filterNot(_.contains("_anon_"))
  }

}

object SslContextFactory {

  val tlsMaxDataSize = math.pow(2, 14).toInt
  val certificateCommonName = "name" // must match what's in the certificates

}
