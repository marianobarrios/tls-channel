package tlschannel

import org.scalatest.FunSuite
import org.scalatest.Matchers
import scala.util.Random
import java.io.IOException
import TestUtil.functionToRunnable
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory

class BlockingTest extends FunSuite with Matchers {

  import SocketWrappers._
    
  val dataSize = 10 * 1024 * 1024 + Random.nextInt(10000)
  val data = Array.ofDim[Byte](dataSize)
  Random.nextBytes(data)

  val margin = Random.nextInt(100)

  val sslEngine = SSLContext.getDefault.createSSLEngine()

  val ciphers = sslEngine.getSupportedCipherSuites
    // Java 8 disabled SSL through another mechanism, ignore that protocol here, to avoid errors 
    .filter(_.startsWith("TLS_"))
    // not using authentication
    .filter(_.contains("_anon_"))

  def writerLoop(writer: Writer, idx: Int, renegotiate: Boolean = false) = TestUtil.cannotFail(s"Error in writer $idx") {
    var remaining = dataSize
    while (remaining > 0) {
      if (renegotiate)
        writer.renegotiate()
      val chunkSize = Random.nextInt(remaining) + 1 // 1 <= chunkSize <= remaining
      writer.write(data, dataSize - remaining, chunkSize)
      remaining -= chunkSize
    }
  }

  def readerLoop(reader: Reader, idx: Int = 0) = TestUtil.cannotFail(s"Error in reader $idx") {
    val receivedData = Array.ofDim[Byte](dataSize + margin)
    var remaining = dataSize
    while (remaining > 0) {
      val chunkSize = Random.nextInt(remaining + margin) + 1 // 1 <= chunkSize <= remaining + margin
      val c = reader.read(receivedData, dataSize - remaining, chunkSize)
      assert(c != -1, "read must not return -1 when there were bytes remaining")
      assert(c <= remaining)
      assert(c > 0, "blocking read must return a positive number")
      remaining -= c
    }
    assert(remaining == 0)
    assert(receivedData.slice(0, dataSize).deep === data.deep)
  }

  /**
   * Test a simplex (one-direction) interaction, with random renegotiations interleaved with the data
   */
  def simplexStream(cipher: String, writer: Writer, reader: Reader) {
    val (_, elapsed) = TestUtil.time {
      val writerThread = new Thread(() => writerLoop(writer, idx = 0, renegotiate = true), "writer")
      val readerThread = new Thread(() => readerLoop(reader), "reader")
      Seq(readerThread, writerThread).foreach(_.start())
      Seq(readerThread, writerThread).foreach(_.join())
      writer.close()
      reader.close()
    }
    info(s"elapsed for $cipher: ${elapsed / 1000} ms")
  }

  /**
   * Test a half-duplex interaction, with renegotiation before reversing the direction of the flow (as in HTTP)
   */
  def halfDuplexStream(
    idx: Int, cipher: String, serverWriter: Writer, clientReader: Reader, clientWriter: Writer, serverReader: Reader) {
    val (_, elapsed) = TestUtil.time {
      val clientWriterThread = new Thread(() => writerLoop(clientWriter, idx), s"client-writer-$idx")
      val serverWriterThread = new Thread(() => writerLoop(serverWriter, idx), s"server-writer-$idx")
      val clientReaderThread = new Thread(() => readerLoop(clientReader, idx), s"client-reader-$idx")
      val serverReaderThread = new Thread(() => readerLoop(serverReader, idx), s"server-reader-$idx")
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
    info(s"elapsed for $cipher: ${elapsed / 1000} ms")
  }

  /**
   * Test a full-duplex interaction, without any renegotiation
   */
  def fullDuplexStream(
    idx: Int, cipher: String, serverWriter: Writer, clientReader: Reader, clientWriter: Writer, serverReader: Reader) {
    val (_, elapsed) = TestUtil.time {
      val clientWriterThread = new Thread(() => writerLoop(clientWriter, idx), s"client-writer-$idx")
      val serverWriterThread = new Thread(() => writerLoop(serverWriter, idx), s"server-writer-$idx")
      val clientReaderThread = new Thread(() => readerLoop(clientReader, idx), s"client-reader-$idx")
      val serverReaderThread = new Thread(() => readerLoop(serverReader, idx), s"server-reader-$idx")
      Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.start())
      Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.join())
      clientWriter.close()
      serverWriter.close()
    }
    info(s"elapsed for $cipher: ${elapsed / 1000} ms")
  }
 
  // NIO -> OLD IO    

  test("nio -> old-io (simplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = nioOld(cipher)
        simplexStream(cipher, clientWriter, serverReader)
      }
    }
  }

  test("nio -> old-io (half duplex)") {
    for ((cipher, idx) <- ciphers.zipWithIndex) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = nioOld(cipher)
        halfDuplexStream(idx, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("nio -> old-io (full duplex)") {
    for ((cipher, idx) <- ciphers.zipWithIndex) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = nioOld(cipher)
        fullDuplexStream(idx, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  // OLD IO -> NIO    

  test("old-io -> nio (simplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = oldNio(cipher)
        simplexStream(cipher, clientWriter, serverReader)
      }
    }
  }

  test("tls: old-io -> nio (half duplex)") {
    for ((cipher, idx) <- ciphers.zipWithIndex) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = oldNio(cipher)
        halfDuplexStream(idx, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("old-io -> nio (full duplex)") {
    for ((cipher, idx) <- ciphers.zipWithIndex) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = oldNio(cipher)
        fullDuplexStream(idx, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  // NIO -> NIO

  test("nio -> nio (simplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = nioNio(cipher)
        simplexStream(cipher, clientWriter, serverReader)
      }
    }
  }

  test("nio -> nio (half duplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = nioNio(cipher)
        halfDuplexStream(0, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("nio -> nio (full duplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = nioNio(cipher)
        fullDuplexStream(0, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }


}