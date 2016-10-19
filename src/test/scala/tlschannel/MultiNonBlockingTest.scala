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
import TestUtil.StreamWithTakeWhileInclusive
import TestUtil.IterableWithForany

class MultiNonBlockingTest extends FunSuite with Matchers with StrictLogging {

  val (cipher, sslContext) = SslContextFactory.standardCipher
  val factory = new SocketPairFactory(sslContext, null)

  val dataSize = SslContextFactory.tlsMaxDataSize * 100

  test("selector loop") {
    val (_, elapsed) = TestUtil.time {
      val pairs = factory.nioNioN(cipher, 100)

      val selector = Selector.open()

      val masterBuffer = ByteBuffer.allocate(dataSize)
      Random.nextBytes(masterBuffer.array)

      val buffers = for (SocketPair(client, server) <- pairs) yield {
        val originBuffer = masterBuffer.duplicate()
        val targetBuffer = ByteBuffer.allocate(dataSize)
        client.plain.configureBlocking(false)
        server.plain.configureBlocking(false)

        client.plain.register(selector, SelectionKey.OP_WRITE, (client, true, originBuffer))
        server.plain.register(selector, SelectionKey.OP_READ, (server, false, targetBuffer))
        (originBuffer, targetBuffer)
      }
      
      val originBuffers = buffers.unzip._1
      val targetBuffers = buffers.unzip._2

      while (originBuffers.forany(_.hasRemaining) || targetBuffers.forany(_.hasRemaining)) {
        logger.trace(s"selecting...")
        val readyChannels = selector.select() // block
        val it = selector.selectedKeys.iterator
        while (it.hasNext) {
          val key = it.next()
          val (selected, isClient, buffer) = key.attachment.asInstanceOf[(SocketGroup, Boolean, ByteBuffer)]
          key.interestOps(0) // delete all operations
          logger.debug(s"selected key: $selected")
          try {
            if (isClient) {
              logger.trace("renegotiating...")
              selected.tls.renegotiate()
              while (buffer.hasRemaining) {
                val c = selected.external.write(buffer)
                assert(c > 0) // the necessity of blocking is communicated with exceptions
              }
            } else {
              while (buffer.hasRemaining) {
                val c = selected.external.read(buffer)
                assert(c > 0) // the necessity of blocking is communicated with exceptions
              }
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

      for (SocketPair(client, server) <- pairs) {
        client.external.close()
        server.external.close()
      }

      for ((originBuffer, targetBuffer) <- buffers) {
        // flip buffers before comparison, as the equals() operates only in remaining bytes
        targetBuffer.flip()
        originBuffer.flip()
        assert(targetBuffer === originBuffer)
      }
    }

    info(f"${elapsed / 1000}%5d ms")
  }

}