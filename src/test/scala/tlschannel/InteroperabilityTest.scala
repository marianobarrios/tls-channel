package tlschannel

import scala.util.Random
import java.net.Socket
import java.nio.channels.ByteChannel
import javax.net.ssl.SSLSocket
import java.nio.ByteBuffer
import org.junit.jupiter.api.Assertions.{assertArrayEquals, assertEquals, assertNotEquals, assertTrue}
import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle
import tlschannel.helpers.TestUtil
import tlschannel.helpers.SslContextFactory
import tlschannel.helpers.SocketPairFactory

@TestInstance(Lifecycle.PER_CLASS)
class InteroperabilityTest {

  import InteroperabilityTest._

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext, SslContextFactory.certificateCommonName)

  def oldNio() = {
    val (client, server) = factory.oldNio(None)
    val clientPair = (new SSLSocketWriter(client), new SocketReader(client))
    val serverPair = (new TlsSocketChannelWriter(server.tls), new ByteChannelReader(server.tls))
    (clientPair, serverPair)
  }

  def nioOld() = {
    val (client, server) = factory.nioOld()
    val clientPair = (new TlsSocketChannelWriter(client.tls), new ByteChannelReader(client.tls))
    val serverPair = (new SSLSocketWriter(server), new SocketReader(server))
    (clientPair, serverPair)
  }

  def oldOld() = {
    val (client, server) = factory.oldOld(None)
    val clientPair = (new SSLSocketWriter(client), new SocketReader(client))
    val serverPair = (new SSLSocketWriter(server), new SocketReader(server))
    (clientPair, serverPair)
  }

  val dataSize = SslContextFactory.tlsMaxDataSize * 10
  val data = Array.ofDim[Byte](dataSize)
  Random.nextBytes(data)

  val margin = Random.nextInt(100)

  def writerLoop(writer: Writer, renegotiate: Boolean = false) = TestUtil.cannotFail {
    var remaining = dataSize
    while (remaining > 0) {
      if (renegotiate)
        writer.renegotiate()
      val chunkSize = Random.nextInt(remaining) + 1 // 1 <= chunkSize <= remaining
      writer.write(data, dataSize - remaining, chunkSize)
      remaining -= chunkSize
    }
  }

  def readerLoop(reader: Reader, idx: Int = 0) = TestUtil.cannotFail {
    val receivedData = Array.ofDim[Byte](dataSize + margin)
    var remaining = dataSize
    while (remaining > 0) {
      val chunkSize = Random.nextInt(remaining + margin) + 1 // 1 <= chunkSize <= remaining + margin
      val c = reader.read(receivedData, dataSize - remaining, chunkSize)
      assertNotEquals(-1, c, "read must not return -1 when there were bytesProduced remaining")
      assertTrue(c <= remaining)
      assertTrue(c > 0, "blocking read must return a positive number")
      remaining -= c
    }
    assertEquals(0, remaining)
    assertArrayEquals(data, receivedData.slice(0, dataSize))
  }

  /** Test a half-duplex interaction, with renegotiation before reversing the direction of the flow (as in HTTP)
    */
  def halfDuplexStream(
      serverWriter: Writer,
      clientReader: Reader,
      clientWriter: Writer,
      serverReader: Reader
  ): Unit = {
    val clientWriterThread = new Thread(() => writerLoop(clientWriter, renegotiate = true), "client-writer")
    val serverWriterThread = new Thread(() => writerLoop(serverWriter, renegotiate = true), "server-writer")
    val clientReaderThread = new Thread(() => readerLoop(clientReader), "client-reader")
    val serverReaderThread = new Thread(() => readerLoop(serverReader), "server-reader")
    Seq(serverReaderThread, clientWriterThread).foreach(_.start())
    Seq(serverReaderThread, clientWriterThread).foreach(_.join())
    clientReaderThread.start()
    // renegotiate three times, to test idempotency
    for (_ <- 1 to 3) {
      serverWriter.renegotiate()
    }
    serverWriterThread.start()
    Seq(clientReaderThread, serverWriterThread).foreach(_.join())
    serverWriter.close()
    clientWriter.close()
  }

  /** Test a full-duplex interaction, without any renegotiation
    */
  def fullDuplexStream(
      serverWriter: Writer,
      clientReader: Reader,
      clientWriter: Writer,
      serverReader: Reader
  ): Unit = {
    val clientWriterThread = new Thread(() => writerLoop(clientWriter), "client-writer")
    val serverWriterThread = new Thread(() => writerLoop(serverWriter), "server-writer")
    val clientReaderThread = new Thread(() => readerLoop(clientReader), "client-reader")
    val serverReaderThread = new Thread(() => readerLoop(serverReader), "server-reader")
    Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.start())
    Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.join())
    clientWriter.close()
    serverWriter.close()
  }

  // OLD IO -> OLD IO

  // "old-io -> old-io (half duplex)
  @Test
  def testOldToOldHalfDuplex(): Unit = {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = oldOld()
    halfDuplexStream(serverWriter, clientReader, clientWriter, serverReader)
  }

  // old-io -> old-io (full duplex)
  @Test
  def testOldToOldFullDuplex(): Unit = {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = oldOld()
    fullDuplexStream(serverWriter, clientReader, clientWriter, serverReader)
  }

  // NIO -> OLD IO

  // nio -> old-io (half duplex)
  @Test
  def testNioToOldHalfDuplex(): Unit = {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = nioOld()
    halfDuplexStream(serverWriter, clientReader, clientWriter, serverReader)
  }

  // nio -> old-io (full duplex)
  @Test
  def testNioToOldFullDuplex(): Unit = {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = nioOld()
    fullDuplexStream(serverWriter, clientReader, clientWriter, serverReader)
  }

  // OLD IO -> NIO

  // old-io -> nio (half duplex)
  @Test
  def testOldToNioHalfDuplex(): Unit = {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = oldNio()
    halfDuplexStream(serverWriter, clientReader, clientWriter, serverReader)
  }

  // old-io -> nio (full duplex)
  @Test
  def testOldToNioFullDuplex(): Unit = {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = oldNio()
    fullDuplexStream(serverWriter, clientReader, clientWriter, serverReader)
  }

}

object InteroperabilityTest {

  trait Reader {
    def read(array: Array[Byte], offset: Int, length: Int): Int
    def close(): Unit
  }

  class SocketReader(socket: Socket) extends Reader {
    private val is = socket.getInputStream
    def read(array: Array[Byte], offset: Int, length: Int) = is.read(array, offset, length)
    def close() = socket.close()
  }

  class ByteChannelReader(socket: ByteChannel) extends Reader {
    def read(array: Array[Byte], offset: Int, length: Int) = socket.read(ByteBuffer.wrap(array, offset, length))
    def close() = socket.close()
  }

  trait Writer {
    def renegotiate(): Unit
    def write(array: Array[Byte], offset: Int, length: Int): Unit
    def close(): Unit
  }

  class SSLSocketWriter(socket: SSLSocket) extends Writer {
    private val os = socket.getOutputStream
    def write(array: Array[Byte], offset: Int, length: Int) = os.write(array, offset, length)
    def renegotiate() = socket.startHandshake()
    def close() = socket.close()
  }

  class TlsSocketChannelWriter(val socket: TlsChannel) extends Writer {

    def write(array: Array[Byte], offset: Int, length: Int) = {
      val buffer = ByteBuffer.wrap(array, offset, length)
      while (buffer.remaining() > 0) {
        val c = socket.write(buffer)
        assertNotEquals(0, c, "blocking write cannot return 0")
      }
    }

    def renegotiate(): Unit = socket.renegotiate()
    def close() = socket.close()
  }

}
