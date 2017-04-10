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
import tlschannel.helpers.SocketPair
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.Loops

class BlockingTest extends FunSuite with Matchers with StrictLogging {

  val (cipher, sslContext) = SslContextFactory.standardCipher
  val factory = new SocketPairFactory(sslContext, SslContextFactory.certificateCommonName)
  val dataSize = SslContextFactory.tlsMaxDataSize * 3

  val data = Array.ofDim[Byte](dataSize)
  Random.nextBytes(data)

  /**
   * Test a half-duplex interaction, with renegotiation before reversing the direction of the flow (as in HTTP)
   */
  test("half duplex (with renegotiations)") {
    val sizes = Stream.iterate(1)(_ * 3).takeWhileInclusive(_ <= SslContextFactory.tlsMaxDataSize)
    val (cipher, sslContext) = SslContextFactory.standardCipher
    for ((size1, size2) <- (sizes zip sizes.reverse)) {
      logger.debug(s"Testing sizes: size1=$size1,size2=$size2")
      val SocketPair(client, server) = factory.nioNio(
        cipher,
        internalClientChunkSize = Some(size1),
        externalClientChunkSize = Some(size2),
        internalServerChunkSize = Some(size1),
        externalServerChunkSize = Some(size2))
      val elapsed = TestUtil.time {
        val clientWriterThread = new Thread(() => Loops.writerLoop(data, client, renegotiate = true), "client-writer")
        val serverWriterThread = new Thread(() => Loops.writerLoop(data, server, renegotiate = true), "server-writer")
        val clientReaderThread = new Thread(() => Loops.readerLoop(data, client), "client-reader")
        val serverReaderThread = new Thread(() => Loops.readerLoop(data, server), "server-reader")
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
      info(f"$size1%5d -eng-> $size2%5d -net-> $size1%5d -eng-> $size2%5d - ${elapsed / 1000}%5d ms")
    }
  }

  /**
   * Test a full-duplex interaction, without any renegotiation
   */
  test("full duplex") {
    val sizes = Stream.iterate(1)(_ * 3).takeWhileInclusive(_ <= SslContextFactory.tlsMaxDataSize)
    for ((size1, size2) <- (sizes zip sizes.reverse)) {
      logger.debug(s"Testing sizes: size1=$size1,size2=$size2")
      val SocketPair(client, server) = factory.nioNio(cipher,
        internalClientChunkSize = Some(size1),
        externalClientChunkSize = Some(size2),
        internalServerChunkSize = Some(size1),
        externalServerChunkSize = Some(size2))
      val elapsed = TestUtil.time {
        val clientWriterThread = new Thread(() => Loops.writerLoop(data, client), "client-writer")
        val serverWriterThread = new Thread(() => Loops.writerLoop(data, server), "server-write")
        val clientReaderThread = new Thread(() => Loops.readerLoop(data, client), "client-reader")
        val serverReaderThread = new Thread(() => Loops.readerLoop(data, server), "server-reader")
        Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.start())
        Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.join())
        client.external.close()
        server.external.close()
      }
      info(f"$size1%5d -eng-> $size2%5d -net-> $size1%5d -eng-> $size2%5d - ${elapsed / 1000}%5d ms")
    }
  }

}

