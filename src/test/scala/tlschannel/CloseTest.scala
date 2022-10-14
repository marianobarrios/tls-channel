package tlschannel

import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException
import com.typesafe.scalalogging.StrictLogging
import org.junit.jupiter.api.Assertions.{assertEquals, assertFalse, assertTrue}
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.api.{Assertions, Test, TestInstance}

import java.nio.channels.AsynchronousCloseException
import tlschannel.helpers.{SocketPairFactory, SslContextFactory, TestUtil}

@TestInstance(Lifecycle.PER_CLASS)
class CloseTest extends StrictLogging {

  val sslContextFactory = new SslContextFactory

  val factory = new SocketPairFactory(sslContextFactory.defaultContext)
  val data = Array[Byte](15)

  /** Less than a TLS message, to force read/write loops
    */
  val internalBufferSize = Some(10)

  @Test
  def testTcpImmediateClose(): Unit = {
    val socketPair =
      factory.nioNio(internalClientChunkSize = internalBufferSize, internalServerChunkSize = internalBufferSize)
    val clientGroup = socketPair.client
    val serverGroup = socketPair.server
    val client = clientGroup.external
    val server = serverGroup.external
    def clientFn(): Unit = TestUtil.cannotFail {
      clientGroup.plain.close()
      assertFalse(clientGroup.tls.shutdownSent())
      assertFalse(clientGroup.tls.shutdownReceived())
      Assertions.assertThrows(classOf[ClosedChannelException], () => client.write(ByteBuffer.wrap(data)))
    }
    def serverFn(): Unit = TestUtil.cannotFail {
      val buffer = ByteBuffer.allocate(1)
      assertEquals(-1, server.read(buffer))
      assertFalse(serverGroup.tls.shutdownReceived())
      assertFalse(serverGroup.tls.shutdownSent())
      // repeated
      assertEquals(-1, server.read(buffer))
    }
    val clientThread = new Thread(() => clientFn(), "client-thread")
    val serverThread = new Thread(() => serverFn(), "server-thread")
    clientThread.start()
    serverThread.start()
    clientThread.join()
    serverThread.join()
    clientGroup.tls.close()
    serverGroup.tls.close()
    SocketPairFactory.checkDeallocation(socketPair)
  }

  @Test
  def testTcpClose(): Unit = {
    val socketPair =
      factory.nioNio(internalClientChunkSize = internalBufferSize, internalServerChunkSize = internalBufferSize)
    val clientGroup = socketPair.client
    val serverGroup = socketPair.server
    val client = clientGroup.external
    val server = serverGroup.external
    def clientFn(): Unit = TestUtil.cannotFail {
      client.write(ByteBuffer.wrap(data))
      clientGroup.plain.close()
      assertFalse(clientGroup.tls.shutdownSent())
      assertFalse(clientGroup.tls.shutdownReceived())
      Assertions.assertThrows(classOf[ClosedChannelException], () => client.write(ByteBuffer.wrap(data)))
    }
    def serverFn(): Unit = TestUtil.cannotFail {
      val buffer = ByteBuffer.allocate(1)
      assertEquals(1, server.read(buffer))
      buffer.flip()
      assertEquals(ByteBuffer.wrap(data), buffer)
      buffer.clear()
      assertEquals(-1, server.read(buffer))
      assertFalse(serverGroup.tls.shutdownReceived())
      assertFalse(serverGroup.tls.shutdownSent())
      // repeated
      assertEquals(-1, server.read(buffer))
    }
    val clientThread = new Thread(() => clientFn(), "client-thread")
    val serverThread = new Thread(() => serverFn(), "server-thread")
    clientThread.start()
    serverThread.start()
    clientThread.join()
    serverThread.join()
    clientGroup.tls.close()
    serverGroup.tls.close()
    SocketPairFactory.checkDeallocation(socketPair)
  }

  @Test
  def testClose(): Unit = {
    val socketPair =
      factory.nioNio(internalClientChunkSize = internalBufferSize, internalServerChunkSize = internalBufferSize)
    val clientGroup = socketPair.client
    val serverGroup = socketPair.server
    val client = clientGroup.external
    val server = serverGroup.external
    def clientFn(): Unit = TestUtil.cannotFail {
      client.write(ByteBuffer.wrap(data))
      client.close()
      assertTrue(clientGroup.tls.shutdownSent())
      assertFalse(clientGroup.tls.shutdownReceived())
      Assertions.assertThrows(classOf[ClosedChannelException], () => client.write(ByteBuffer.wrap(data)))
    }
    def serverFn(): Unit = TestUtil.cannotFail {
      val buffer = ByteBuffer.allocate(1)
      assertEquals(1, server.read(buffer))
      buffer.flip()
      assertEquals(ByteBuffer.wrap(data), buffer)
      buffer.clear()
      assertEquals(-1, server.read(buffer))
      assertTrue(serverGroup.tls.shutdownReceived())
      assertFalse(serverGroup.tls.shutdownSent())
      // repeated
      assertEquals(-1, server.read(buffer))
      server.close()
      Assertions.assertThrows(classOf[ClosedChannelException], () => server.read(buffer))
    }
    val clientThread = new Thread(() => clientFn(), "client-thread")
    val serverThread = new Thread(() => serverFn(), "server-thread")
    clientThread.start()
    serverThread.start()
    clientThread.join()
    serverThread.join()
    SocketPairFactory.checkDeallocation(socketPair)
  }

  @Test
  def testCloseAndWait(): Unit = {
    val socketPair = factory.nioNio(
      internalClientChunkSize = internalBufferSize,
      internalServerChunkSize = internalBufferSize,
      waitForCloseConfirmation = true
    )
    val clientGroup = socketPair.client
    val serverGroup = socketPair.server
    val client = clientGroup.external
    val server = serverGroup.external
    def clientFn(): Unit = TestUtil.cannotFail {
      client.write(ByteBuffer.wrap(data))
      client.close()
      assertTrue(clientGroup.tls.shutdownReceived())
      assertTrue(clientGroup.tls.shutdownSent())
      Assertions.assertThrows(classOf[ClosedChannelException], () => client.write(ByteBuffer.wrap(data)))
    }
    def serverFn(): Unit = TestUtil.cannotFail {
      val buffer = ByteBuffer.allocate(1)
      assertEquals(1, server.read(buffer))
      buffer.flip()
      assertEquals(ByteBuffer.wrap(data), buffer)
      buffer.clear()
      assertEquals(-1, server.read(buffer))
      // repeated
      assertEquals(-1, server.read(buffer))
      server.close()
      assertTrue(serverGroup.tls.shutdownReceived())
      assertTrue(serverGroup.tls.shutdownSent())
      Assertions.assertThrows(classOf[ClosedChannelException], () => server.read(buffer))
    }
    val clientThread = new Thread(() => clientFn(), "client-thread")
    val serverThread = new Thread(() => serverFn(), "server-thread")
    clientThread.start()
    serverThread.start()
    clientThread.join()
    serverThread.join()
    SocketPairFactory.checkDeallocation(socketPair)
  }

  @Test
  def testCloseAndWaitForever(): Unit = {
    val socketPair = factory.nioNio(
      internalClientChunkSize = internalBufferSize,
      internalServerChunkSize = internalBufferSize,
      waitForCloseConfirmation = true
    )
    val clientGroup = socketPair.client
    val serverGroup = socketPair.server
    val client = clientGroup.external
    val server = serverGroup.external
    def clientFn(): Unit = TestUtil.cannotFail {
      client.write(ByteBuffer.wrap(data))
      client.close()
    }
    def serverFn(): Unit = TestUtil.cannotFail {
      val buffer = ByteBuffer.allocate(1)
      assertEquals(1, server.read(buffer))
      buffer.flip()
      assertEquals(ByteBuffer.wrap(data), buffer)
      buffer.clear()
      assertEquals(-1, server.read(buffer))
      assertTrue(serverGroup.tls.shutdownReceived())
      assertFalse(serverGroup.tls.shutdownSent())
      // repeated
      assertEquals(-1, server.read(buffer))
    }
    val clientThread = new Thread(() => clientFn(), "client-thread")
    val serverThread = new Thread(() => serverFn(), "server-thread")
    clientThread.start()
    serverThread.start()
    clientThread.join(5000)
    serverThread.join()
    assertTrue(clientThread.isAlive)
    serverGroup.tls.close()
    clientThread.join()
    SocketPairFactory.checkDeallocation(socketPair)
  }

  @Test
  def testShutdownAndForget(): Unit = {
    val socketPair =
      factory.nioNio(internalClientChunkSize = internalBufferSize, internalServerChunkSize = internalBufferSize)
    val clientGroup = socketPair.client
    val serverGroup = socketPair.server
    val client = clientGroup.external
    val server = serverGroup.external
    def clientFn(): Unit = TestUtil.cannotFail {
      client.write(ByteBuffer.wrap(data))
      assertFalse(clientGroup.tls.shutdown())
      assertFalse(clientGroup.tls.shutdownReceived())
      assertTrue(clientGroup.tls.shutdownSent())
      Assertions.assertThrows(classOf[ClosedChannelException], () => client.write(ByteBuffer.wrap(data)))
    }
    def serverFn(): Unit = TestUtil.cannotFail {
      val buffer = ByteBuffer.allocate(1)
      assertEquals(1, server.read(buffer))
      buffer.flip()
      assertEquals(ByteBuffer.wrap(data), buffer)
      buffer.clear()
      assertEquals(-1, server.read(buffer))
      assertTrue(serverGroup.tls.shutdownReceived())
      assertFalse(serverGroup.tls.shutdownSent())
    }
    val clientThread = new Thread(() => clientFn(), "client-thread")
    val serverThread = new Thread(() => serverFn(), "server-thread")
    clientThread.start()
    serverThread.start()
    clientThread.join()
    serverThread.join()
    client.close()
    server.close()
    SocketPairFactory.checkDeallocation(socketPair)
  }

  @Test
  def testShutdownAndWait(): Unit = {
    val socketPair =
      factory.nioNio(internalClientChunkSize = internalBufferSize, internalServerChunkSize = internalBufferSize)
    val clientGroup = socketPair.client
    val serverGroup = socketPair.server
    val client = clientGroup.external
    val server = serverGroup.external
    def clientFn(): Unit = TestUtil.cannotFail {
      client.write(ByteBuffer.wrap(data))
      // send first close_notify
      assertFalse(clientGroup.tls.shutdown())
      assertFalse(clientGroup.tls.shutdownReceived())
      assertTrue(clientGroup.tls.shutdownSent())
      Assertions.assertThrows(classOf[ClosedChannelException], () => client.write(ByteBuffer.wrap(data)))
      // wait for second close_notify
      assertTrue(clientGroup.tls.shutdown())
      assertTrue(clientGroup.tls.shutdownReceived())
      assertTrue(clientGroup.tls.shutdownSent())
    }
    def serverFn(): Unit = TestUtil.cannotFail {
      val buffer = ByteBuffer.allocate(1)
      assertEquals(1, server.read(buffer))
      buffer.flip()
      assertEquals(ByteBuffer.wrap(data), buffer)
      buffer.clear()
      assertEquals(-1, server.read(buffer))
      assertTrue(serverGroup.tls.shutdownReceived())
      assertFalse(serverGroup.tls.shutdownSent())
      assertEquals(-1, server.read(buffer))
      // send second close_notify
      assertTrue(serverGroup.tls.shutdown())
      assertTrue(serverGroup.tls.shutdownReceived())
      assertTrue(serverGroup.tls.shutdownSent())
    }
    val clientThread = new Thread(() => clientFn(), "client-thread")
    val serverThread = new Thread(() => serverFn(), "server-thread")
    clientThread.start()
    serverThread.start()
    clientThread.join()
    serverThread.join()
    client.close()
    server.close()
    SocketPairFactory.checkDeallocation(socketPair)
  }

  @Test
  def testShutdownAndWaitForever(): Unit = {
    val socketPair =
      factory.nioNio(internalClientChunkSize = internalBufferSize, internalServerChunkSize = internalBufferSize)
    val clientGroup = socketPair.client
    val serverGroup = socketPair.server
    val client = clientGroup.external
    val server = serverGroup.external
    def clientFn(): Unit = TestUtil.cannotFail {
      client.write(ByteBuffer.wrap(data))
      // send first close_notify
      assertFalse(clientGroup.tls.shutdown())
      assertFalse(clientGroup.tls.shutdownReceived())
      assertTrue(clientGroup.tls.shutdownSent())
      Assertions.assertThrows(classOf[ClosedChannelException], () => client.write(ByteBuffer.wrap(data)))
      // wait for second close_notify
      Assertions.assertThrows(classOf[AsynchronousCloseException], () => clientGroup.tls.shutdown())
    }
    def serverFn(): Unit = TestUtil.cannotFail {
      val buffer = ByteBuffer.allocate(1)
      assertEquals(1, server.read(buffer))
      buffer.flip()
      assertEquals(ByteBuffer.wrap(data), buffer)
      buffer.clear()
      assertEquals(-1, server.read(buffer))
      assertTrue(serverGroup.tls.shutdownReceived())
      assertFalse(serverGroup.tls.shutdownSent())
      assertEquals(-1, server.read(buffer))
      // do not send second close_notify
    }
    val clientThread = new Thread(() => clientFn(), "client-thread")
    val serverThread = new Thread(() => serverFn(), "server-thread")
    clientThread.start()
    serverThread.start()
    serverThread.join()
    clientThread.join(5000)
    assertTrue(clientThread.isAlive)
    client.close()
    server.close()
    SocketPairFactory.checkDeallocation(socketPair)
  }

}
