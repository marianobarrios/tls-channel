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

class NonBlockingTest extends FunSuite with Matchers with StrictLogging {

  val (cipher, sslContext) = SslContextFactory.standardCipher
  val factory = new SocketPairFactory(sslContext, null)

  val dataSize = SslContextFactory.tlsMaxDataSize * 3

  test("selector loop") {
    val sizes = Stream.iterate(1)(_ * 3).takeWhileInclusive(_ <= SslContextFactory.tlsMaxDataSize)
    for ((size1, size2) <- (sizes zip sizes.reverse)) {
      val (_, elapsed) = TestUtil.time {
        logger.debug(s"Sizes: size1=$size1,size2=$size2")
        val SocketPair(client, server) = factory.nioNio(
          cipher,
          internalClientChunkSize = size1,
          externalClientChunkSize = size2,
          internalServerChunkSize = size1,
          externalServerChunkSize = size2)

        val selector = Selector.open()

        client.plain.configureBlocking(false)
        server.plain.configureBlocking(false)

        client.plain.register(selector, SelectionKey.OP_WRITE)
        server.plain.register(selector, SelectionKey.OP_READ)

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
                case client.plain =>
                  logger.debug("renegotiating...")
                  client.tls.renegotiate()
                  while (originBuffer.hasRemaining) {
                    val c = client.external.write(originBuffer)
                    assert(c > 0) // the necessity of blocking is communicated with exceptions
                  }
                case server.plain =>
                  while (targetBuffer.hasRemaining) {
                    val c = server.external.read(targetBuffer)
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
      info(f"$size1%5d -eng-> $size2%5d -net-> $size1%5d -eng-> $size2%5d - ${elapsed / 1000}%5d ms")
    }
  }

}