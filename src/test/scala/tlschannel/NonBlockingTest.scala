package tlschannel

import org.scalatest.FunSuite
import org.scalatest.Matchers
import javax.net.ssl.SSLContext
import scala.util.Random
import java.nio.ByteBuffer
import java.nio.channels.Selector
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel
import com.typesafe.scalalogging.Logging
import com.typesafe.scalalogging.slf4j.StrictLogging

class NonBlockingTest extends FunSuite with Matchers with StrictLogging {

  val sslEngine = SSLContext.getDefault.createSSLEngine

  val ciphers = sslEngine.getSupportedCipherSuites
    // Java 8 disabled SSL through another mechanism, ignore that protocol here, to avoid errors 
    .filter(_.startsWith("TLS_"))
    // not using authentication
    .filter(_.contains("_anon_"))

  val factory = new SocketPairFactory(7777)
  val dataSize = 20 * 1024 * 1024 + Random.nextInt(1000)
  
  test("selector loop") {
    for (cipher <- ciphers) {
      info(s"Testing with cipher: $cipher")
      val (_, elapsed) = TestUtil.time {
        testNioLoop(cipher)
      }
      info(s"Elapsed: ${elapsed / 1000}ms")
    }
  }

  def testNioLoop(cipher: String) = {
    val (clients, servers) = factory.nioNio(cipher)
    val (tlsClientOrig, rawClient) = clients
    val (tlsServerOrig, rawServer) = servers
    val selector = Selector.open()

    val tlsClient = new RandomizedChunkingByteChannel(tlsClientOrig)
    val tlsServer = new RandomizedChunkingByteChannel(tlsServerOrig)
    
    rawClient.configureBlocking(false)
    rawServer.configureBlocking(false)

    rawClient.register(selector, SelectionKey.OP_WRITE)
    rawServer.register(selector, SelectionKey.OP_READ)

    val originBuffer = ByteBuffer.allocate(dataSize)
    val targetBuffer = ByteBuffer.allocate(dataSize)
    
    Random.nextBytes(originBuffer.array)
    
    while (originBuffer.hasRemaining || targetBuffer.hasRemaining) {
      logger.debug(s"selecting...")
      val readyChannels = selector.select() // block
      val it = selector.selectedKeys.iterator
      while (it.hasNext) {
        val key = it.next()
        val selected = key.channel
        key.interestOps(0) // delete all operations
        logger.debug(s"selected key: $selected")
        try {
          selected match {
            case `rawClient` =>
              logger.debug("renegotiating...")
              tlsClientOrig.renegotiate()
              while (originBuffer.hasRemaining) {
                val c = tlsClient.write(originBuffer)
                assert(c > 0) // the necessity of blocking is communicated with exceptions
              }
            case `rawServer` =>
              while (targetBuffer.hasRemaining) {
                val c = tlsServer.read(targetBuffer)
                assert(c > 0) // the necessity of blocking is communicated with exceptions
              }
            case _ => 
              throw new AssertionError
          }
        } catch {
          case e: NeedsWriteException =>
            logger.debug(s"read threw exception: ${e.getClass}")
            key.interestOps(SelectionKey.OP_WRITE)
          case e: NeedsReadException =>
            logger.debug(s"read threw exception: ${e.getClass}")
            key.interestOps(SelectionKey.OP_READ)
        }
        it.remove()
      }
    }

    // flip buffers before comparison, as the equals() operates only in remaining bytes
    targetBuffer.flip()
    originBuffer.flip()
    assert(targetBuffer === originBuffer)

  }

}