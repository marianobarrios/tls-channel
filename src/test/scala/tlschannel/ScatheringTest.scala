package tlschannel

import org.scalatest.FunSuite
import org.scalatest.Matchers
import com.typesafe.scalalogging.slf4j.StrictLogging
import java.nio.channels.ByteChannel
import tlschannel.helpers.TestUtil.StreamWithTakeWhileInclusive
import java.nio.ByteBuffer
import scala.util.Random
import tlschannel.helpers.TestUtil.functionToRunnable
import tlschannel.helpers.TestUtil
import tlschannel.helpers.SslContextFactory
import tlschannel.helpers.Loops
import tlschannel.helpers.SocketPair
import tlschannel.helpers.SocketPairFactory

class ScatheringTest extends FunSuite with Matchers with StrictLogging {

  val (cipher, sslContext) = SslContextFactory.standardCipher
  val factory = new SocketPairFactory(sslContext, SslContextFactory.certificateCommonName)
  val dataSize = SslContextFactory.tlsMaxDataSize * 3

  val data = Array.ofDim[Byte](dataSize)
  Random.nextBytes(data)

  /**
   * Test a half-duplex interaction, with renegotiation before reversing the direction of the flow (as in HTTP)
   */
  test("half duplex") {
    val sizes = Stream.iterate(1)(_ * 3).takeWhileInclusive(_ <= SslContextFactory.tlsMaxDataSize)
    val (cipher, sslContext) = SslContextFactory.standardCipher
    val SocketPair(client, server) = factory.nioNio(cipher)
    val elapsed = TestUtil.time {
      val clientWriterThread = new Thread(() => Loops.writerLoop(data, client, scathering = true), "client-writer")
      val serverWriterThread = new Thread(() => Loops.writerLoop(data, server, scathering = true), "server-writer")
      val clientReaderThread = new Thread(() => Loops.readerLoop(data, client, gathering = true), "client-reader")
      val serverReaderThread = new Thread(() => Loops.readerLoop(data, server, gathering = true), "server-reader")
      Seq(serverReaderThread, clientWriterThread).foreach(_.start())
      Seq(serverReaderThread, clientWriterThread).foreach(_.join())
      clientReaderThread.start()
      serverWriterThread.start()
      Seq(clientReaderThread, serverWriterThread).foreach(_.join())
      server.external.close()
      client.external.close()
    }
    info(f"${elapsed / 1000}%5d ms")
  }

}

