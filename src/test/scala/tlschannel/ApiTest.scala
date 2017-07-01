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

import tlschannel.helpers.TestUtil.fnToConsumer
import tlschannel.impl.{BufferHolder, ByteBufferSet, TlsChannelImpl}
import java.util.Optional

import tlschannel.helpers.TestUtil

class ApiTest extends FunSuite with Matchers {

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
      (_: SSLSession) => (),
      true,
      new HeapBufferAllocator,
      new HeapBufferAllocator,
      false /* waitForCloseConfirmation */)
  }

  test("reading into a read-only buffer") {
    val socket = newSocket()
    intercept[IllegalArgumentException] {
      socket.read(new ByteBufferSet(ByteBuffer.allocate(1).asReadOnlyBuffer()))
    }
  }

  test("reading into a buffer without remaining capacity") {
    val socket = newSocket()
    assert(socket.read(new ByteBufferSet(ByteBuffer.allocate(0))) === 0, "read must return zero when the buffer was empty")
  }

  test("writing from an empty buffer should work") {
    val socket = newSocket()
    socket.write(new ByteBufferSet(ByteBuffer.allocate(0))) // empty write
  }

}