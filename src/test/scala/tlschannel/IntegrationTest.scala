package tlschannel

import org.scalatest.FunSuite
import org.scalatest.Matchers
import scala.util.Random
import java.io.IOException
import TestUtil.functionToRunnable

class IntegrationTest extends FunSuite with Matchers with Asserts {

  val dataSize = 1024 * 1024 + Random.nextInt(1000)
  val data = Array.ofDim[Byte](dataSize)
  Random.nextBytes(data)

  val bigData = Array.ofDim[Byte](10 * 1000 * 1000)

  val margin = Random.nextInt(100)

  val ciphers = Seq(
    "SSL_DH_anon_EXPORT_WITH_DES40_CBC_SHA",
    "SSL_DH_anon_EXPORT_WITH_RC4_40_MD5",
    "SSL_DH_anon_WITH_3DES_EDE_CBC_SHA",
    "TLS_DH_anon_WITH_AES_128_CBC_SHA256",
    "TLS_DH_anon_WITH_AES_128_GCM_SHA256",
    "SSL_DH_anon_WITH_DES_CBC_SHA",
    "SSL_DH_anon_WITH_RC4_128_MD5",
    "TLS_ECDH_anon_WITH_3DES_EDE_CBC_SHA",
    "TLS_ECDH_anon_WITH_AES_128_CBC_SHA",
    "TLS_ECDH_anon_WITH_NULL_SHA",
    "TLS_ECDH_anon_WITH_RC4_128_SHA")
    
  def writerLoop(writer: Writer, idx: Int, renegotiate: Boolean = false) = TestUtil.cannotFail(s"Error in writer $idx") {
    var remaining = dataSize
    while (remaining > 0) {
      if (Random.nextInt(20) == 0)
        writer.write(data, 0, 0) // empty write
      if (renegotiate && Random.nextInt(10) == 0)
        writer.renegotiate()
      val chunkSize = Random.nextInt(remaining) + 1 // 1 <= chunkSize <= remaining
      writer.write(data, dataSize - remaining, chunkSize)
      remaining -= chunkSize
    }
  }

  def readerLoop(cipher: String, reader: Reader, idx: Int = 0) = TestUtil.cannotFail(s"Error in reader $idx") {
    val receivedData = Array.ofDim[Byte](dataSize + margin)
    var remaining = dataSize
    while (remaining > 0) {
      if (Random.nextInt(20) == 0)
        assert(reader.read(Array.ofDim(0), 0, 0) === 0, "read must return zero when the buffer was empty")
      val chunkSize = Random.nextInt(remaining + margin) + 1 // 1 <= chunkSize <= remaining + margin
      val c = reader.read(receivedData, dataSize - remaining, chunkSize)
      assert(c != -1, "read must not return -1 when there were bytes remaining")
      assert(c <= remaining)
      remaining -= c
      if (c == 0) {
        /*
         * The read returns zero when the socket is non-blocking and the buffer is empty. Sleep some time to avoid
         * using too much CPU
         */
        Thread.sleep(ioWaitMs)
      }
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
      val readerThread = new Thread(() => readerLoop(cipher, reader), "reader")
      Seq(readerThread, writerThread).foreach(_.start())
      readerThread.join()
      writerThread.join()
      writer.close()
      // test two times, to ensure that the second time it behaves the same
      for (_ <- 1 to 2) {
        assert(reader.read(Array.ofDim(1), 0, 1) == -1, "final read must return -1, signaling EOF")
      }
      reader.close()
      // test two times, to ensure that the second time it behaves the same
      for (i <- 1 to 2) {
        intercept[IOException] {
          writer.write(bigData, 0, bigData.length)
        }
      }
    }
    info(s"elapsed for $cipher: ${elapsed / 1000} ms")
  }

  /**
   * Test an interaction in which the reader closes the socket
   */
  def closingStream(cipher: String, writer: Writer, reader: Reader) {
    val (_, elapsed) = TestUtil.time {
      reader.close()
      // test two times, to ensure that the second time it behaves the same
      for (i <- 1 to 2) {
        intercept[IOException] {
          writer.write(bigData, 0, bigData.length)
        }
      }
      writer.close()
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
      val clientReaderThread = new Thread(() => readerLoop(cipher, clientReader, idx), s"client-reader-$idx")
      val serverReaderThread = new Thread(() => readerLoop(cipher, serverReader, idx), s"server-reader-$idx")
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
      val clientReaderThread = new Thread(() => readerLoop(cipher, clientReader, idx), s"client-reader-$idx")
      val serverReaderThread = new Thread(() => readerLoop(cipher, serverReader, idx), s"server-reader-$idx")
      serverReaderThread.start()
      Seq(clientWriterThread).foreach(_.start())
      Seq(clientReaderThread, serverWriterThread).foreach(_.start())
      Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.join())
      clientWriter.close()
      serverWriter.close()
    }
    info(s"elapsed for $cipher: ${elapsed / 1000} ms")
  }

}