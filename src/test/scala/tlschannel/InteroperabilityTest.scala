package tlschannel

import org.scalatest.FunSuite
import org.scalatest.Matchers
import scala.util.Random
import java.net.Socket
import java.nio.channels.ByteChannel
import javax.net.ssl.SSLSocket
import java.nio.ByteBuffer
import com.typesafe.scalalogging.StrictLogging
import tlschannel.helpers.TestUtil
import tlschannel.helpers.SslContextFactory
import tlschannel.helpers.SocketPairFactory

class InteroperabilityTest extends FunSuite with Matchers with StrictLogging {

  import InteroperabilityTest._

  val sslContextFactory = new SslContextFactory
  val (cipher, sslContext) = sslContextFactory.standardCipher
  val factory = new SocketPairFactory(sslContext, SslContextFactory.certificateCommonName)

  def oldNio(cipher: String) = {
    val (client, server) = factory.oldNio(cipher)
    val clientPair = (new SSLSocketWriter(client), new SocketReader(client))
    val serverPair = (new TlsSocketChannelWriter(server.tls), new ByteChannelReader(server.tls))
    (clientPair, serverPair)
  }

  def nioOld(cipher: String) = {
    val (client, server) = factory.nioOld(cipher)
    val clientPair = (new TlsSocketChannelWriter(client.tls), new ByteChannelReader(client.tls))
    val serverPair = (new SSLSocketWriter(server), new SocketReader(server))
    (clientPair, serverPair)
  }

  def oldOld(cipher: String) = {
    val (client, server) = factory.oldOld(cipher)
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
      assert(c != -1, "read must not return -1 when there were bytesProduced remaining")
      assert(c <= remaining)
      assert(c > 0, "blocking read must return a positive number")
      remaining -= c
    }
    assert(remaining == 0)
    assert(receivedData.slice(0, dataSize).deep === data.deep)
  }

  /**
   * Test a half-duplex interaction, with renegotiation before reversing the direction of the flow (as in HTTP)
   */
  def halfDuplexStream(cipher: String, serverWriter: Writer, clientReader: Reader, clientWriter: Writer, serverReader: Reader) {
    val elapsed = TestUtil.time {
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
    info(s"elapsed: ${elapsed.toMillis} ms")
  }

  /**
   * Test a full-duplex interaction, without any renegotiation
   */
  def fullDuplexStream(cipher: String, serverWriter: Writer, clientReader: Reader, clientWriter: Writer, serverReader: Reader) {
    val elapsed = TestUtil.time {
      val clientWriterThread = new Thread(() => writerLoop(clientWriter), "client-writer")
      val serverWriterThread = new Thread(() => writerLoop(serverWriter), "server-writer")
      val clientReaderThread = new Thread(() => readerLoop(clientReader), "client-reader")
      val serverReaderThread = new Thread(() => readerLoop(serverReader), "server-reader")
      Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.start())
      Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.join())
      clientWriter.close()
      serverWriter.close()
    }
    info(s"elapsed: ${elapsed.toMillis} ms")
  }

  // OLD IO -> OLD IO    

  test("old-io -> old-io (half duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = oldOld(cipher)
    halfDuplexStream(cipher, serverWriter, clientReader, clientWriter, serverReader)
  }

  test("old-io -> old-io (full duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = oldOld(cipher)
    fullDuplexStream(cipher, serverWriter, clientReader, clientWriter, serverReader)
  }

  // NIO -> OLD IO    

  test("nio -> old-io (half duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = nioOld(cipher)
    halfDuplexStream(cipher, serverWriter, clientReader, clientWriter, serverReader)
  }

  test("nio -> old-io (full duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = nioOld(cipher)
    fullDuplexStream(cipher, serverWriter, clientReader, clientWriter, serverReader)
  }

  // OLD IO -> NIO    

  test("old-io -> nio (half duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = oldNio(cipher)
    halfDuplexStream(cipher, serverWriter, clientReader, clientWriter, serverReader)
  }

  test("old-io -> nio (full duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = oldNio(cipher)
    fullDuplexStream(cipher, serverWriter, clientReader, clientWriter, serverReader)
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

  class ByteChannelReader(socket: ByteChannel) extends Reader with Matchers {
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

  class TlsSocketChannelWriter(val socket: TlsChannel) extends Writer with Matchers {

    def write(array: Array[Byte], offset: Int, length: Int) = {
      val buffer = ByteBuffer.wrap(array, offset, length)
      while (buffer.remaining() > 0) {
        val c = socket.write(buffer)
        assert(c != 0, "blocking write cannot return 0")
      }
    }

    def renegotiate(): Unit = socket.renegotiate()
    def close() = socket.close()

  }

}