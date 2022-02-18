package tlschannel

import io.netty.buffer.ByteBufAllocator
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import io.netty.handler.ssl.util.SimpleTrustManagerFactory
import javax.net.ssl.{ManagerFactoryParameters, SSLEngine, TrustManager, TrustManagerFactory, X509ExtendedTrustManager}
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.ByteChannel
import java.security.KeyStore
import java.security.cert.X509Certificate

import org.scalatest.funsuite.AnyFunSuite

class NettySslEngineTest extends AnyFunSuite {

  import NettySslEngineTest._

  test("dummy handshake as minimal sanity test") {
    val ctx = SslContextBuilder
      .forClient()
      .sslProvider(SslProvider.OPENSSL)
      .trustManager(dummyTrustManagerFactory)
      .protocols("TLSv1.3")
      .build()
    val sslEngine = ctx.newEngine(ByteBufAllocator.DEFAULT, "test", 0)

    val channel = ClientTlsChannel
      .newBuilder(new DummyByteChannel(), sslEngine)
      .withEncryptedBufferAllocator(new HeapBufferAllocator())
      .build()

    assertThrows[NeedsWriteException] {
      channel.handshake()
    }
  }

}

object NettySslEngineTest {

  private val dummyTrustManagerFactory: TrustManagerFactory = new SimpleTrustManagerFactory() {
    private val trustManagers = Array[TrustManager](new DummyTrustManager())
    override def engineInit(keyStore: KeyStore): Unit = {}
    override def engineInit(params: ManagerFactoryParameters): Unit = {}
    override def engineGetTrustManagers(): Array[TrustManager] = trustManagers
  }

  private class DummyByteChannel extends ByteChannel {
    override def isOpen = false
    override def close(): Unit = {}
    override def write(src: ByteBuffer) = 0
    override def read(dst: ByteBuffer) = 0
  }

  private class DummyTrustManager extends X509ExtendedTrustManager {
    override def checkClientTrusted(certs: Array[X509Certificate], s: String): Unit = {}
    override def checkServerTrusted(certs: Array[X509Certificate], s: String): Unit = {}
    override def getAcceptedIssuers: Array[X509Certificate] = Array[X509Certificate]()
    override def checkClientTrusted(certs: Array[X509Certificate], s: String, socket: Socket): Unit = {}
    override def checkServerTrusted(certs: Array[X509Certificate], s: String, socket: Socket): Unit = {}
    override def checkClientTrusted(certs: Array[X509Certificate], s: String, sslEngine: SSLEngine): Unit = {}
    override def checkServerTrusted(certs: Array[X509Certificate], s: String, sslEngine: SSLEngine): Unit = {}
  }
}
