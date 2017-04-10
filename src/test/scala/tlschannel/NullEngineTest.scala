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
import tlschannel.helpers.SocketGroup

/**
 * Test using a null engine (pass-through).	The purpose of the test is to remove
 * the overhead of the real {@link SSLEngine} to be able to test the overhead of the
 * {@link TlsSocketChannel}.
 */
class NullEngineTest extends FunSuite with Matchers with StrictLogging {

  val (cipher, sslContext) = SslContextFactory.standardCipher
  val factory = new SocketPairFactory(sslContext, SslContextFactory.certificateCommonName)
  val dataSize = 1 * 1024 * 1024 * 1024

  test("null engine - half duplex - heap buffers") {
    val sizes = Stream.iterate(512)(_ * 3).takeWhileInclusive(_ <= SslContextFactory.tlsMaxDataSize)
    val (cipher, sslContext) = SslContextFactory.standardCipher
    val elapsedTotal = TestUtil.time {
      for (size1 <- sizes) {
        logger.debug(s"Testing sizes: size1=$size1")
        val (client, server) = factory.nullNioNio(
          internalClientChunkSize = Some(size1),
          internalServerChunkSize = Some(size1),
          new HeapBufferAllocator)
        val elapsed = TestUtil.time {
          val clientWriterThread = new Thread(() => NullEngineTest.writerLoop(dataSize, client), "client-writer")
          val serverWriterThread = new Thread(() => NullEngineTest.writerLoop(dataSize, server), "server-writer")
          val clientReaderThread = new Thread(() => NullEngineTest.readerLoop(dataSize, client), "client-reader")
          val serverReaderThread = new Thread(() => NullEngineTest.readerLoop(dataSize, server), "server-reader")
          Seq(serverReaderThread, clientWriterThread).foreach(_.start())
          Seq(serverReaderThread, clientWriterThread).foreach(_.join())
          clientReaderThread.start()
          serverWriterThread.start()
          Seq(clientReaderThread, serverWriterThread).foreach(_.join())
          server.external.close()
          client.external.close()
        }
        info(f"-eng-> $size1%5d -net-> $size1%5d -eng-> - ${elapsed / 1000}%5d ms")
      }
    }
    info(f"Total time: ${elapsedTotal / 1000}%5d ms")
  }
  
    test("null engine - half duplex - direct buffers") {
    val sizes = Stream.iterate(512)(_ * 3).takeWhileInclusive(_ <= SslContextFactory.tlsMaxDataSize)
    val (cipher, sslContext) = SslContextFactory.standardCipher
    val elapsedTotal = TestUtil.time {
      for (size1 <- sizes) {
        logger.debug(s"Testing sizes: size1=$size1")
        val (client, server) = factory.nullNioNio(
          internalClientChunkSize = Some(size1),
          internalServerChunkSize = Some(size1),
          new DirectBufferAllocator)
        val elapsed = TestUtil.time {
          val clientWriterThread = new Thread(() => NullEngineTest.writerLoop(dataSize, client), "client-writer")
          val serverWriterThread = new Thread(() => NullEngineTest.writerLoop(dataSize, server), "server-writer")
          val clientReaderThread = new Thread(() => NullEngineTest.readerLoop(dataSize, client), "client-reader")
          val serverReaderThread = new Thread(() => NullEngineTest.readerLoop(dataSize, server), "server-reader")
          Seq(serverReaderThread, clientWriterThread).foreach(_.start())
          Seq(serverReaderThread, clientWriterThread).foreach(_.join())
          clientReaderThread.start()
          serverWriterThread.start()
          Seq(clientReaderThread, serverWriterThread).foreach(_.join())
          server.external.close()
          client.external.close()
        }
        info(f"-eng-> $size1%5d -net-> $size1%5d -eng-> - ${elapsed / 1000}%5d ms")
      }
    }
    info(f"Total time: ${elapsedTotal / 1000}%5d ms")
  }

}

object NullEngineTest extends Matchers with StrictLogging {

  def writerLoop(dataSize: Int, socketGroup: SocketGroup): Unit = TestUtil.cannotFail("Error in writer") {
    logger.debug(s"Starting writer loop")
    val originData = ByteBuffer.allocate(SslContextFactory.tlsMaxDataSize)
    var bytesWritten = 0
    while (bytesWritten < dataSize) {
      if (!originData.hasRemaining)
        originData.position(0)
      val c = socketGroup.external.write(originData)
      assert(c > 0)
      bytesWritten += c
    }
    logger.debug("Finalizing writer loop")
  }

  def readerLoop(dataSize: Int, socketGroup: SocketGroup): Unit = TestUtil.cannotFail("Error in reader") {
    logger.debug("Starting reader loop")
    val receivedData = ByteBuffer.allocate(SslContextFactory.tlsMaxDataSize)
    var bytesRead = 0
    while (bytesRead < dataSize) {
      if (!receivedData.hasRemaining)
        receivedData.position(0)
      val c = socketGroup.external.read(receivedData)
      assert(c > 0, "blocking read must return a positive number")
      bytesRead += c
    }
    logger.debug("Finalizing reader loop")
  }

}
