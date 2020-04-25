package tlschannel

import java.nio.channels.Channels
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

import javax.net.ssl.SSLContext
import tlschannel.impl.{BufferHolder, ImmutableByteBufferSet, TlsChannelImpl}
import java.util.Optional

import org.junit.runner.RunWith
import org.scalatest.Assertions
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ApiTest extends AnyFunSuite with Assertions {

  val arraySize = 1024

  val readChannel = Channels.newChannel(new ByteArrayInputStream(new Array(arraySize)))
  val writeChannel = Channels.newChannel(new ByteArrayOutputStream(arraySize))

  def newSocket() = {
    val sslEngine = SSLContext.getDefault.createSSLEngine
    new TlsChannelImpl(
      readChannel,
      writeChannel,
      sslEngine,
      Optional.empty[BufferHolder],
      _ => (),
      true,
      new TrackingAllocator(new HeapBufferAllocator),
      new TrackingAllocator(new HeapBufferAllocator),
      true /* releaseBuffers */,
      false /* waitForCloseConfirmation */
    )
  }

  test("reading into a read-only buffer") {
    val socket = newSocket()
    intercept[IllegalArgumentException] {
      socket.read(ByteBuffer.allocate(1).asReadOnlyBuffer())
    }
  }

  test("reading into a buffer without remaining capacity") {
    val socket = newSocket()
    assert(socket.read(ByteBuffer.allocate(0)) == 0, "read must return zero when the buffer was empty")
  }

}
