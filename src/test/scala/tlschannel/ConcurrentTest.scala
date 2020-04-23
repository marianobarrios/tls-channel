package tlschannel

import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

import com.typesafe.scalalogging.StrictLogging
import org.junit.runner.RunWith
import org.scalatest.Assertions
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner
import tlschannel.helpers.{SocketGroup, SocketPairFactory, SslContextFactory, TestUtil}

@RunWith(classOf[JUnitRunner])
class ConcurrentTest extends AnyFunSuite with Assertions with StrictLogging {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)
  val dataSize = 500_000_000
  val bufferSize = 2000

  /**
    * Test several parties writing concurrently
    */
  test("write-size thread-safety") {
    val socketPair = factory.nioNio()
    val elapsed = TestUtil.time {
      val clientWriterThread1 = new Thread(() => writerLoop(dataSize, 'a', socketPair.client), "client-writer-1")
      val clientWriterThread2 = new Thread(() => writerLoop(dataSize, 'b', socketPair.client), "client-writer-2")
      val clientWriterThread3 = new Thread(() => writerLoop(dataSize, 'c', socketPair.client), "client-writer-3")
      val clientWriterThread4 = new Thread(() => writerLoop(dataSize, 'd', socketPair.client), "client-writer-4")
      val serverReaderThread = new Thread(() => readerLoop(dataSize * 4, socketPair.server), "server-reader")
      Seq(serverReaderThread, clientWriterThread1, clientWriterThread2, clientWriterThread3, clientWriterThread4)
        .foreach(_.start())
      Seq(clientWriterThread1, clientWriterThread2, clientWriterThread3, clientWriterThread4).foreach(_.join())
      socketPair.client.external.close()
      serverReaderThread.join()
      SocketPairFactory.checkDeallocation(socketPair)
    }
    info(f"${elapsed.toMillis}%5d ms")
  }

  /**
    * Test several parties reading concurrently
    */
  test("read-size thread-safety") {
    val socketPair = factory.nioNio()
    val elapsed: Duration = TestUtil.time {
      val clientWriterThread = new Thread(() => writerLoop(dataSize, 'a', socketPair.client), "client-writer")
      val totalRead = new AtomicLong
      val serverReaderThread1 = new Thread(() => readerLoopUntilEof(socketPair.server, totalRead), "server-reader-1")
      val serverReaderThread2 = new Thread(() => readerLoopUntilEof(socketPair.server, totalRead), "server-reader-2")
      Seq(serverReaderThread1, serverReaderThread2, clientWriterThread).foreach(_.start())
      clientWriterThread.join()
      socketPair.client.external.close()
      Seq(serverReaderThread1, serverReaderThread2).foreach(_.join())
      SocketPairFactory.checkDeallocation(socketPair)
      assert(totalRead.get() == dataSize)
    }
    info(f"${elapsed.toMillis}%5d ms")
  }

  private def writerLoop(size: Int, char: Char, socketGroup: SocketGroup): Unit = TestUtil.cannotFail {
    logger.debug(s"Starting writer loop, size: $size")
    var bytesRemaining = size
    val bufferArray = Array.fill[Byte](bufferSize)(char.toByte)
    while (bytesRemaining > 0) {
      val buffer = ByteBuffer.wrap(bufferArray, 0, math.min(bufferSize, bytesRemaining))
      while (buffer.hasRemaining) {
        val c = socketGroup.external.write(buffer)
        assert(c > 0, "blocking write must return a positive number")
        bytesRemaining -= c.toInt
        assert(bytesRemaining >= 0)
      }
    }
    logger.debug("Finalizing writer loop")
  }

  private def readerLoop(size: Int, socketGroup: SocketGroup): Unit = TestUtil.cannotFail {
    logger.debug(s"Starting reader loop. Size: $size")
    val readArray = Array.ofDim[Byte](bufferSize)
    var bytesRemaining = size
    while (bytesRemaining > 0) {
      val readBuffer = ByteBuffer.wrap(readArray, 0, math.min(bufferSize, bytesRemaining))
      val c = socketGroup.external.read(readBuffer)
      assert(c > 0, "blocking read must return a positive number")
      bytesRemaining -= c
      assert(bytesRemaining >= 0)
    }
    logger.debug("Finalizing reader loop")
  }

  private def readerLoopUntilEof(socketGroup: SocketGroup, accumulator: AtomicLong): Unit = TestUtil.cannotFail {
    logger.debug(s"Starting reader loop.")
    val readArray = Array.ofDim[Byte](bufferSize)
    while (true) {
      val readBuffer = ByteBuffer.wrap(readArray, 0, bufferSize)
      val c = socketGroup.external.read(readBuffer)
      if (c == -1) {
        logger.debug("Finalizing reader loop")
        return
      }
      assert(c > 0, "blocking read must return a positive number")
      accumulator.addAndGet(c)
    }
    fail()
  }

}
