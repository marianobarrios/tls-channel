package tlschannel

import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.api.{Assertions, Test, TestInstance}

import java.nio.channels.Channels
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import tlschannel.impl.{BufferHolder, ByteBufferSet, TlsChannelImpl}

import java.util.Optional

@TestInstance(Lifecycle.PER_CLASS)
class ApiTest {

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

  @Test
  def testReadIntoReadOnlyBuffer(): Unit = {
    val socket = newSocket()
    Assertions.assertThrows(
      classOf[IllegalArgumentException],
      () => socket.read(new ByteBufferSet(ByteBuffer.allocate(1).asReadOnlyBuffer()))
    )
  }

  @Test
  def testReadIntoBufferWithoutCapacity(): Unit = {
    val socket = newSocket()
    Assertions.assertEquals(
      0,
      socket.read(new ByteBufferSet(ByteBuffer.allocate(0))),
      "read must return zero when the buffer was empty"
    )
  }

}
