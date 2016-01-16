package tlschannel

import org.scalatest.FunSuite
import org.scalatest.Matchers
import scala.util.Random
import java.io.IOException
import TestUtil.functionToRunnable
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import java.nio.channels.ByteChannel
import java.nio.ByteBuffer

class BlockingTest extends FunSuite with Matchers {

  val factory = new SocketPairFactory(7777)

  val dataSize = 10 * 1024 * 1024 + Random.nextInt(10000)
  
  val data = Array.ofDim[Byte](dataSize)
  Random.nextBytes(data)

  val sslEngine = SSLContext.getDefault.createSSLEngine()

  val ciphers = sslEngine.getSupportedCipherSuites
    // Java 8 disabled SSL through another mechanism, ignore that protocol here, to avoid errors 
    .filter(_.startsWith("TLS_"))
    // not using authentication
    .filter(_.contains("_anon_"))

  def writerLoop(writer: ByteChannel, rawWriter: TlsSocketChannel, renegotiate: Boolean = false): Unit = TestUtil.cannotFail("Error in writer") {
    val originData = ByteBuffer.wrap(data)
    while (originData.hasRemaining) {
      if (renegotiate)
        rawWriter.renegotiate()
      val c = writer.write(originData)
      assert(c > 0)
    }
  }

  def readerLoop(reader: ByteChannel): Unit = TestUtil.cannotFail("Error in reader") {
    val receivedData = ByteBuffer.allocate(dataSize)
    while (receivedData.hasRemaining) {
      val c = reader.read(receivedData)
      assert(c > 0, "blocking read must return a positive number")
    }
    assert(receivedData.array.deep === data.deep)
  }

  /**
   * Test a half-duplex interaction, with renegotiation before reversing the direction of the flow (as in HTTP)
   */
  test("half duplex") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((client, _), (server, _)) = factory.nioNio(cipher)
        val (_, elapsed) = TestUtil.time {
          val clientWriterThread = new Thread(() => writerLoop(new ChunkingByteChannel(client), client, renegotiate = true), "client-writer")
          val serverWriterThread = new Thread(() => writerLoop(new ChunkingByteChannel(server), server, renegotiate = true), "server-writer")
          val clientReaderThread = new Thread(() => readerLoop(new ChunkingByteChannel(client)), "client-reader")
          val serverReaderThread = new Thread(() => readerLoop(new ChunkingByteChannel(server)), "server-reader")
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
        info(s"elapsed for $cipher: ${elapsed / 1000} ms")
      }
    }
  }

  /**
   * Test a full-duplex interaction, without any renegotiation
   */
  test("full duplex") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((client, _), (server, _)) = factory.nioNio(cipher)
        val (_, elapsed) = TestUtil.time {
          val clientWriterThread = new Thread(() => writerLoop(new ChunkingByteChannel(client), client), "client-writer")
          val serverWriterThread = new Thread(() => writerLoop(new ChunkingByteChannel(server), server), "server-write")
          val clientReaderThread = new Thread(() => readerLoop(new ChunkingByteChannel(client)), "client-reader")
          val serverReaderThread = new Thread(() => readerLoop(new ChunkingByteChannel(server)), "server-reader")
          Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.start())
          Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.join())
          client.close()
          server.close()
        }
        info(s"elapsed for $cipher: ${elapsed / 1000} ms")
      }
    }
  }

}