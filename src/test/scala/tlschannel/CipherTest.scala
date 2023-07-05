package tlschannel

import org.junit.jupiter.api.{DynamicTest, Test, TestFactory, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle

import javax.net.ssl.SSLContext
import tlschannel.helpers.SslContextFactory
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.Loops

import java.util
import scala.jdk.CollectionConverters._

@TestInstance(Lifecycle.PER_CLASS)
class CipherTest {

  val protocols = {
    val allProtocols = SSLContext.getDefault.getSupportedSSLParameters.getProtocols.toSeq
    allProtocols.filterNot(_ == "SSLv2Hello")
  }

  val dataSize = 200 * 1000

  // Test a half-duplex interaction, with renegotiation before reversing the direction of the flow (as in HTTP)
  @TestFactory
  def testHalfDuplexWithRenegotiation(): util.Collection[DynamicTest] = {
    println("testHalfDuplexWithRenegotiation():")
    val tests = for {
      protocol <- protocols
      ctxFactory = new SslContextFactory(protocol)
      cipher <- ctxFactory.allCiphers
    } yield {
      DynamicTest.dynamicTest(
        s"testHalfDuplexWithRenegotiation() - protocol: $protocol, cipher: $cipher",
        () => {
          val socketFactory = new SocketPairFactory(ctxFactory.defaultContext)
          val socketPair = socketFactory.nioNio(Some(cipher))
          Loops.halfDuplex(socketPair, dataSize, renegotiation = protocol <= "TLSv1.2")
          val actualProtocol = socketPair.client.tls.getSslEngine.getSession.getProtocol
          val p = s"$protocol ($actualProtocol)"
          println(f"$p%-18s $cipher%-50s")
        }
      )
    }
    tests.asJava
  }

  // Test a full-duplex interaction, without any renegotiation
  @TestFactory
  def testFullDuplex(): util.Collection[DynamicTest] = {
    val tests = for {
      protocol <- protocols
      ctxFactory = new SslContextFactory(protocol)
      cipher <- ctxFactory.allCiphers
    } yield {
      DynamicTest.dynamicTest(
        s"testFullDuplex() - protocol: $protocol, cipher: $cipher",
        () => {
          val socketFactory = new SocketPairFactory(ctxFactory.defaultContext)
          val socketPair = socketFactory.nioNio(Some(cipher))
          Loops.fullDuplex(socketPair, dataSize)
          val actualProtocol = socketPair.client.tls.getSslEngine.getSession.getProtocol
          val p = s"$protocol ($actualProtocol)"
          println(f"$p%-18s $cipher%-50s")
        }
      )
    }
    tests.asJava
  }

}
