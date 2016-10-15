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
        val ((client, rawClient), (server, rawServer)) = socketFactories(sslContext).nioNio(cipher)
        val (_, elapsed) = TestUtil.time {
          val clientWriterThread = new Thread(() => BlockingTest.writerLoop(data, client, client, renegotiate = true), "client-writer")
          val serverWriterThread = new Thread(() => BlockingTest.writerLoop(data, server, server, renegotiate = true), "server-writer")
          val clientReaderThread = new Thread(() => BlockingTest.readerLoop(data, client), "client-reader")
          val serverReaderThread = new Thread(() => BlockingTest.readerLoop(data, server), "server-reader")
          Seq(serverReaderThread, clientWriterThread).foreach(_.start())
          Seq(serverReaderThread, clientWriterThread).foreach(_.join())
          clientReaderThread.start()
          // renegotiate three times, to test idempotency
          for (_ <- 1 to 3) {
            server.renegotiate()
          }
          serverWriterThread.start()
          Seq(clientReaderThread, serverWriterThread).foreach(_.join())
          server.close()
          client.close()
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
        val ((client, _), (server, _)) = socketFactories(sslContext).nioNio(cipher)
        val (_, elapsed) = TestUtil.time {
          val clientWriterThread = new Thread(() => BlockingTest.writerLoop(data, client, client), "client-writer")
          val serverWriterThread = new Thread(() => BlockingTest.writerLoop(data, server, server), "server-write")
          val clientReaderThread = new Thread(() => BlockingTest.readerLoop(data, client), "client-reader")
          val serverReaderThread = new Thread(() => BlockingTest.readerLoop(data, server), "server-reader")
          Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.start())
          Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.join())
          client.close()
          server.close()
        }
        info(f"$cipher%-45s - ${elapsed / 1000}%5d ms")
      }
    }
  }

}