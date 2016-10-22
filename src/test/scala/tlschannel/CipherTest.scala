package tlschannel

import org.scalatest.FunSuite
import org.scalatest.Matchers
import com.typesafe.scalalogging.slf4j.StrictLogging
import java.nio.channels.ByteChannel
import TestUtil.StreamWithTakeWhileInclusive
import java.nio.ByteBuffer
import scala.util.Random
import TestUtil.functionToRunnable

class CipherTest extends FunSuite with Matchers with StrictLogging {

  val factory = new SocketPairFactory(SslContextFactory.authenticatedContext, SslContextFactory.certificateCommonName)
  val anonFactory = new SocketPairFactory(SslContextFactory.anonContext, null)

  val socketFactories = Map(
    SslContextFactory.authenticatedContext -> factory,
    SslContextFactory.anonContext -> anonFactory)
  val dataSize = SslContextFactory.tlsMaxDataSize * 10

  val data = Array.ofDim[Byte](dataSize)
  Random.nextBytes(data)

  /**
   * Test a half-duplex interaction, with renegotiation before reversing the direction of the flow (as in HTTP)
   */
  test("half duplex (with renegotiations)") {
    for ((cipher, sslContext) <- SslContextFactory.allCiphers) {
      withClue(cipher + ": ") {
        logger.debug(s"Testing cipher: $cipher")
        val SocketPair(client, server) = socketFactories(sslContext).nioNio(cipher)
        val elapsed = TestUtil.time {
          val clientWriterThread = new Thread(() => BlockingTest.writerLoop(data, client.external, client.tls, renegotiate = true), "client-writer")
          val serverWriterThread = new Thread(() => BlockingTest.writerLoop(data, server.external, server.tls, renegotiate = true), "server-writer")
          val clientReaderThread = new Thread(() => BlockingTest.readerLoop(data, client.external), "client-reader")
          val serverReaderThread = new Thread(() => BlockingTest.readerLoop(data, server.external), "server-reader")
          Seq(serverReaderThread, clientWriterThread).foreach(_.start())
          Seq(serverReaderThread, clientWriterThread).foreach(_.join())
          clientReaderThread.start()
          // renegotiate three times, to test idempotency
          for (_ <- 1 to 3) {
            server.tls.renegotiate()
          }
          serverWriterThread.start()
          Seq(clientReaderThread, serverWriterThread).foreach(_.join())
          server.external.close()
          client.external.close()
        }
        info(f"$cipher%-45s - ${elapsed / 1000}%5d ms")
      }
    }
  }

  /**
   * Test a full-duplex interaction, without any renegotiation
   */
  test("full duplex") {
    for ((cipher, sslContext) <- SslContextFactory.allCiphers) {
      withClue(cipher + ": ") {
        logger.debug(s"Testing cipher: $cipher")
        val SocketPair(client, server) = socketFactories(sslContext).nioNio(cipher)
        val elapsed = TestUtil.time {
          val clientWriterThread = new Thread(() => BlockingTest.writerLoop(data, client.external, client.tls), "client-writer")
          val serverWriterThread = new Thread(() => BlockingTest.writerLoop(data, server.external, server.tls), "server-write")
          val clientReaderThread = new Thread(() => BlockingTest.readerLoop(data, client.external), "client-reader")
          val serverReaderThread = new Thread(() => BlockingTest.readerLoop(data, server.external), "server-reader")
          Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.start())
          Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.join())
          client.external.close()
          server.external.close()
        }
        info(f"$cipher%-45s - ${elapsed / 1000}%5d ms")
      }
    }
  }

}