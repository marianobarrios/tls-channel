package tlschannel

import org.scalatest.FunSuite
import org.scalatest.Matchers
import java.nio.channels.Channels
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.io.IOException
import javax.net.ssl.SSLContext
import java.util.function.Consumer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession

import TestUtil.fnToConsumer

class ApiTest extends FunSuite with Matchers {

  val arraySize = 1024
  val inEncryptedSize = 100 * 1024

  val readChannel = Channels.newChannel(new ByteArrayInputStream(new Array(arraySize)))
  val writeChannel = Channels.newChannel(new ByteArrayOutputStream(arraySize))

  def newSocket() = {
    val sslEngine = SSLContext.getDefault.createSSLEngine
    new TlsSocketChannelImpl(readChannel, writeChannel, sslEngine, ByteBuffer.allocate(inEncryptedSize), (_: SSLSession) => ())
  }
  
  test("reading into a read-only buffer") {
    val socket = newSocket()
    intercept[IllegalArgumentException] {
      socket.read(ByteBuffer.allocate(1).asReadOnlyBuffer())
    }
  }

  test("reading into a buffer without remaining capacity") {
    val socket = newSocket()
    assert(socket.read(ByteBuffer.allocate(0)) === 0, "read must return zero when the buffer was empty")
  }
  
  test("writing from an empty buffer should work") {
    val socket = newSocket()
    socket.write(ByteBuffer.allocate(0)) // empty write
  }

  test("using socket after close") {
    val socket = newSocket()
    socket.close()
    intercept[IOException] {
      socket.write(ByteBuffer.allocate(arraySize))
    }
    assert(-1 === socket.read(ByteBuffer.allocate(arraySize)))
  }

}