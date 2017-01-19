package tlschannel

import org.scalatest.FunSuite
import org.scalatest.Matchers
import com.typesafe.scalalogging.slf4j.StrictLogging
import java.nio.channels.ByteChannel
import TestUtil.StreamWithTakeWhileInclusive
import java.nio.ByteBuffer
import scala.util.Random
import TestUtil.functionToRunnable

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
      val clientWriterThread = new Thread(() => ScatheringTest.writerLoop(data, client.tls), "client-writer")
      val serverWriterThread = new Thread(() => ScatheringTest.writerLoop(data, server.tls), "server-writer")
      val clientReaderThread = new Thread(() => ScatheringTest.readerLoop(data, client.tls), "client-reader")
      val serverReaderThread = new Thread(() => ScatheringTest.readerLoop(data, server.tls), "server-reader")
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

object ScatheringTest extends Matchers with StrictLogging {

  def writerLoop(data: Array[Byte], writer: TlsSocketChannel): Unit = TestUtil.cannotFail("Error in writer") {
    val renegotiatePeriod = 10000
    logger.debug(s"Starting writer loop")
    val originData = multiWrap(data)
    var bytesWrittenSinceRenegotiation = 0L
    while (remaining(originData) > 0) {
      val c = writer.write(originData)
      bytesWrittenSinceRenegotiation += c
      assert(c > 0)
    }
    logger.debug("Finalizing writer loop")
  }

  def multiWrap(data: Array[Byte]) = {
    Array(ByteBuffer.allocate(0), ByteBuffer.wrap(data), ByteBuffer.allocate(0))
  }

  def remaining(buffers: Array[ByteBuffer]) = {
    buffers.map(_.remaining.toLong).sum
  }

  def readerLoop(data: Array[Byte], reader: TlsSocketChannel): Unit = TestUtil.cannotFail("Error in reader") {
    logger.debug("Starting reader loop")
    val receivedData = ByteBuffer.allocate(data.length)
    val receivedDataArray = Array(ByteBuffer.allocate(0), receivedData, ByteBuffer.allocate(0))
    while (remaining(receivedDataArray) > 0) {
      val c = reader.read(receivedDataArray)
      assert(c > 0, "blocking read must return a positive number")
    }
    assert(receivedData.array.deep === data.deep)
    logger.debug("Finalizing reader loop")
  }

}
