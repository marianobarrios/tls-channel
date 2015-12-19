package tlschannel

import org.scalatest.FunSuite
import org.scalatest.Matchers
import java.nio.channels.Channels
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.io.IOException
import javax.net.ssl.SSLContext

class ApiTest extends FunSuite with Matchers {

  val arraySize = 1024
  val inEncryptedSize = 100 * 1024

  val readChannel = Channels.newChannel(new ByteArrayInputStream(new Array(arraySize)))
  val writeChannel = Channels.newChannel(new ByteArrayOutputStream(arraySize))

  test("reading into a read-only buffer") {
    val sslEngine = SSLContext.getDefault.createSSLEngine
    val socket = new TlsSocketChannelImpl(readChannel, writeChannel, sslEngine, ByteBuffer.allocate(inEncryptedSize), s => ())
    intercept[IllegalArgumentException] {
      socket.read(ByteBuffer.allocate(1).asReadOnlyBuffer())
    }
  }

  test("it should not be possible to use socket after close") {
    val sslEngine = SSLContext.getDefault.createSSLEngine
    val socket = new TlsSocketChannelImpl(readChannel, writeChannel, sslEngine, ByteBuffer.allocate(inEncryptedSize), s => ())
    socket.close()
    intercept[IOException] {
      socket.write(ByteBuffer.allocate(arraySize))
    }
    assert(-1 === socket.read(ByteBuffer.allocate(arraySize)))
  }

}