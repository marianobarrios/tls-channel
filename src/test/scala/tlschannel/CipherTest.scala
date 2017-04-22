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
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SocketPair
import tlschannel.helpers.Loops

class CipherTest extends FunSuite with Matchers with StrictLogging {

  val factory = new SocketPairFactory(SslContextFactory.authenticatedContext, SslContextFactory.certificateCommonName)
  val anonFactory = new SocketPairFactory(SslContextFactory.anonContext, SslContextFactory.certificateCommonName)

  val socketFactories = Map(
    SslContextFactory.authenticatedContext -> factory,
    SslContextFactory.anonContext -> anonFactory)

  val dataSize = 150 * 1000

  /**
   * Test a half-duplex interaction, with renegotiation before reversing the direction of the flow (as in HTTP)
   */
  test("half duplex (with renegotiations)") {
    for ((cipher, sslContext) <- SslContextFactory.allCiphers) {
      withClue(cipher + ": ") {
        logger.debug(s"Testing cipher: $cipher")
        val SocketPair(client, server) = socketFactories(sslContext).nioNio(cipher)
        val elapsed = TestUtil.time {
          val clientWriterThread = new Thread(() => Loops.writerLoop(dataSize, client, renegotiate = true), "client-writer")
          val serverWriterThread = new Thread(() => Loops.writerLoop(dataSize, server, renegotiate = true), "server-writer")
          val clientReaderThread = new Thread(() => Loops.readerLoop(dataSize, client), "client-reader")
          val serverReaderThread = new Thread(() => Loops.readerLoop(dataSize, server), "server-reader")
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
          val clientWriterThread = new Thread(() => Loops.writerLoop(dataSize, client), "client-writer")
          val serverWriterThread = new Thread(() => Loops.writerLoop(dataSize, server), "server-write")
          val clientReaderThread = new Thread(() => Loops.readerLoop(dataSize, client), "client-reader")
          val serverReaderThread = new Thread(() => Loops.readerLoop(dataSize, server), "server-reader")
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