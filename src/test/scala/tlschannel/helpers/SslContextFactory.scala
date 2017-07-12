package tlschannel.helpers

import javax.net.ssl.SSLContext
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory
import java.security.KeyStore

object SslContextFactory {



  val certificateCommonName = "name" // must match what's in the certificates
  
  val authenticatedContext = {
    val sslContext = SSLContext.getInstance("TLSv1.2")

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
    val ctx = SSLContext.getInstance("TLSv1.2")
    ctx.init(null, null, null)
    ctx
  }
  
  val authenticatedCiphers = ciphers(authenticatedContext)
    .filterNot(_.contains("_anon_"))
    .filterNot(_ == "TLS_EMPTY_RENEGOTIATION_INFO_SCSV") // this is not a real cipher, but a hack actually
    .filterNot(_.startsWith("TLS_ECDH_RSA_"))
    .filterNot(_.startsWith("TLS_KRB5_"))
    .toSeq
  
  val anonCiphers = ciphers(anonContext).filter(_.contains("_anon_")).toSeq

  val allCiphers = {
    val ret = authenticatedCiphers.map((_, authenticatedContext)) ++ anonCiphers.map((_, anonContext))
    ret.sortBy(_._1)
  }
  
  val standardCipher = ("TLS_DH_anon_WITH_AES_128_CBC_SHA", anonContext)
  
  private def ciphers(ctx: SSLContext) = {
    ctx.createSSLEngine().getSupportedCipherSuites
      // Java 8 disabled SSL through another mechanism, ignore that protocol here, to avoid errors 
      .filter(_.startsWith("TLS_"))
  }
  
  val tlsMaxDataSize = math.pow(2, 14).toInt
  
}
