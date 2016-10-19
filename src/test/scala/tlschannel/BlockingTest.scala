package tlschannel

import org.scalatest.FunSuite
import org.scalatest.Matchers
import com.typesafe.scalalogging.slf4j.StrictLogging
import java.nio.channels.ByteChannel
import TestUtil.StreamWithTakeWhileInclusive
import java.nio.ByteBuffer
import scala.util.Random
import TestUtil.functionToRunnable

class BlockingTest extends FunSuite with Matchers with StrictLogging {

  val (cipher, sslContext) = SslContextFactory.standardCipher
  val factory = new SocketPairFactory(sslContext, null)
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
      val SocketPair(SocketGroup(client, clientChannel, _), SocketGroup(server, serverChannel, _)) = factory.nioNio(
        cipher,
        internalClientChunkSize = size1,
        externalClientChunkSize = size2,
        internalServerChunkSize = size1,
        externalServerChunkSize = size2)
      val (_, elapsed) = TestUtil.time {
        val clientWriterThread = new Thread(() => BlockingTest.writerLoop(data, client, clientChannel, renegotiate = true), "client-writer")
        val serverWriterThread = new Thread(() => BlockingTest.writerLoop(data, server, serverChannel, renegotiate = true), "server-writer")
        val clientReaderThread = new Thread(() => BlockingTest.readerLoop(data, client), "client-reader")
        val serverReaderThread = new Thread(() => BlockingTest.readerLoop(data, server), "server-reader")
        Seq(serverReaderThread, clientWriterThread).foreach(_.start())
        Seq(serverReaderThread, clientWriterThread).foreach(_.join())
        clientReaderThread.start()
        // renegotiate three times, to test idempotency
        for (_ <- 1 to 3) {
          serverChannel.renegotiate()
        }
        serverWriterThread.start()
        Seq(clientReaderThread, serverWriterThread).foreach(_.join())
        server.close()
        client.close()
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
        internalClientChunkSize = size1,
        externalClientChunkSize = size2,
        internalServerChunkSize = size1,
        externalServerChunkSize = size2)
      val (_, elapsed) = TestUtil.time {
        val clientWriterThread = new Thread(() => BlockingTest.writerLoop(data, client.external, client.tls), "client-writer")
        val serverWriterThread = new Thread(() => BlockingTest.writerLoop(data, server.external, server.tls), "server-write")
        val clientReaderThread = new Thread(() => BlockingTest.readerLoop(data, client.external), "client-reader")
        val serverReaderThread = new Thread(() => BlockingTest.readerLoop(data, server.external), "server-reader")
        Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.start())
        Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.join())
        client.external.close()
        server.external.close()
      }
      info(f"$size1%5d -eng-> $size2%5d -net-> $size1%5d -eng-> $size2%5d - ${elapsed / 1000}%5d ms")
    }
  }

}

object BlockingTest extends Matchers with StrictLogging {

  def writerLoop(data: Array[Byte], writer: ByteChannel, rawWriter: TlsSocketChannel, renegotiate: Boolean = false): Unit = TestUtil.cannotFail("Error in writer") {
    val renegotiatePeriod = 10000
    logger.debug(s"Starting writer loop, renegotiate:$renegotiate")
    val originData = ByteBuffer.wrap(data)
    var bytesWrittenSinceRenegotiation = 0
    while (originData.hasRemaining) {
      if (renegotiate && bytesWrittenSinceRenegotiation > renegotiatePeriod) {
        rawWriter.renegotiate()
        bytesWrittenSinceRenegotiation = 0
      }
      val c = writer.write(originData)
      bytesWrittenSinceRenegotiation += c
      assert(c > 0)
    }
    logger.debug("Finalizing writer loop")
  }

  def readerLoop(data: Array[Byte], reader: ByteChannel): Unit = TestUtil.cannotFail("Error in reader") {
    logger.debug("Starting reader loop")
    val receivedData = ByteBuffer.allocate(data.length)
    while (receivedData.hasRemaining) {
      val c = reader.read(receivedData)
      assert(c > 0, "blocking read must return a positive number")
    }
    assert(receivedData.array.deep === data.deep)
    logger.debug("Finalizing reader loop")
  }

}
