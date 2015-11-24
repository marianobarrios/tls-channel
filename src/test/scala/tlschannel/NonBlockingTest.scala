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

class NonBlockingTest extends FunSuite with Matchers with Asserts with StrictLogging {

  val sslEngine = SSLContext.getDefault.createSSLEngine

  val ciphers = sslEngine.getSupportedCipherSuites
    // Java 8 disabled SSL through another mechanism, ignore that protocol here, to avoid errors 
    .filter(_.startsWith("TLS_"))
    // not using authentication
    .filter(_.contains("_anon_"))

  val factory = new SocketPairFactory(7777)

  val dataSize = 10 * 1024 * 1024 + Random.nextInt(1000)

  logger.debug(s"data size: $dataSize")
  val data = Array.ofDim[Byte](dataSize)
  Random.nextBytes(data)

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
    
    val (clients, servers) = factory.tls_Nio_Nio(cipher)
    val (tlsClient, rawClient) = clients
    val (tlsServer, rawServer) = servers
    val selector = Selector.open()

    rawClient.configureBlocking(false)
    rawServer.configureBlocking(false)

    rawClient.register(selector, SelectionKey.OP_WRITE)
    rawServer.register(selector, SelectionKey.OP_READ)

    val margin = Random.nextInt(100)
    val receivedData = Array.ofDim[Byte](dataSize + margin)

    var remainingWrite = dataSize
    var remainingRead = dataSize

    def clientWrite() = {
      while (remainingWrite > 0) {
        if (Random.nextInt(20) == 0) {
          logger.debug("doing empty write")
          tlsClient.write(ByteBuffer.wrap(data, 0, 0))
        }
        if (Random.nextInt(20) == 0) {
          logger.debug("renegotiating...")
          tlsClient.renegotiate()
        }
        val chunkSize = Random.nextInt(remainingWrite) + 1 // 1 <= chunkSize <= remainingWrite
        val ret = tlsClient.write(ByteBuffer.wrap(data, dataSize - remainingWrite, chunkSize))
        assert(ret > 0) // the necessity of blocking is communicated with exceptions
        assert(ret <= chunkSize)
        remainingWrite -= ret
      }
    }

    def serverRead() = {
      while (remainingRead > 0) {
        if (Random.nextInt(40) == 0) {
          logger.debug("doing empty read")
          val c = tlsServer.read(ByteBuffer.wrap(Array.ofDim(0), 0, 0))
          assert(c === 0, "read must return zero when the buffer was empty")
        }
        val chunkSize = Random.nextInt(remainingRead + margin) + 1 // 1 <= chunkSize <= remainingRead + margin
        val c = tlsServer.read(ByteBuffer.wrap(receivedData, dataSize - remainingRead, chunkSize))
        assert(c > 0) // the necessity of blocking is communicated with exceptions
        assert(c <= remainingRead)
        remainingRead -= c
      }
    }

    while (remainingWrite > 0 || remainingRead > 0) {
      logger.debug(s"selecting...")
      val readyChannels = selector.select() // block
      val selectedKeys = selector.selectedKeys
      val it = selectedKeys.iterator
      while (it.hasNext) {
        val key = it.next()
        val selected = key.channel()
        key.interestOps(0) // delete all operations
        logger.debug(s"selected key: $selected")
        try {
          selected match {
            case `rawClient` => clientWrite()
            case `rawServer` => serverRead()
            case _ => throw new AssertionError
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

    assert(receivedData.slice(0, dataSize).deep === data.deep)

  }

}