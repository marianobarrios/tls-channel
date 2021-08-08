package tlschannel

import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException

import com.typesafe.scalalogging.StrictLogging
import java.nio.channels.AsynchronousCloseException

import org.scalatest.Assertions
import org.scalatest.funsuite.AnyFunSuite
import tlschannel.helpers.{SocketPairFactory, SslContextFactory, TestUtil}

class CloseTest extends AnyFunSuite with Assertions with StrictLogging {

  val sslContextFactory = new SslContextFactory

  val factory = new SocketPairFactory(sslContextFactory.defaultContext)
  val data = Array[Byte](15)

  /**
    * Less than a TLS message, to force read/write loops
    */
  val internalBufferSize = Some(10)

  test("TlsChannel - TCP immediate close") {
    val socketPair =
      factory.nioNio(internalClientChunkSize = internalBufferSize, internalServerChunkSize = internalBufferSize)
    val clientGroup = socketPair.client
    val serverGroup = socketPair.server
    val client = clientGroup.external
    val server = serverGroup.external
    def clientFn(): Unit = TestUtil.cannotFail {
      clientGroup.plain.close()
      assert(!clientGroup.tls.shutdownSent())
      assert(!clientGroup.tls.shutdownReceived())
      intercept[ClosedChannelException] {
        client.write(ByteBuffer.wrap(data))
      }
    }
    def serverFn(): Unit = TestUtil.cannotFail {
      val buffer = ByteBuffer.allocate(1)
      assert(server.read(buffer) == -1)
      assert(!serverGroup.tls.shutdownReceived())
      assert(!serverGroup.tls.shutdownSent())
      // repeated
      assert(server.read(buffer) == -1)
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

  test("TlsChannel - TCP close") {
    val socketPair =
      factory.nioNio(internalClientChunkSize = internalBufferSize, internalServerChunkSize = internalBufferSize)
    val clientGroup = socketPair.client
    val serverGroup = socketPair.server
    val client = clientGroup.external
    val server = serverGroup.external
    def clientFn(): Unit = TestUtil.cannotFail {
      client.write(ByteBuffer.wrap(data))
      clientGroup.plain.close()
      assert(!clientGroup.tls.shutdownSent())
      assert(!clientGroup.tls.shutdownReceived())
      intercept[ClosedChannelException] {
        client.write(ByteBuffer.wrap(data))
      }
    }
    def serverFn(): Unit = TestUtil.cannotFail {
      val buffer = ByteBuffer.allocate(1)
      assert(server.read(buffer) == 1)
      buffer.flip()
      assert(buffer == ByteBuffer.wrap(data))
      buffer.clear()
      assert(server.read(buffer) == -1)
      assert(!serverGroup.tls.shutdownReceived())
      assert(!serverGroup.tls.shutdownSent())
      // repeated
      assert(server.read(buffer) == -1)
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

  test("TlsChannel - close") {
    val socketPair =
      factory.nioNio(internalClientChunkSize = internalBufferSize, internalServerChunkSize = internalBufferSize)
    val clientGroup = socketPair.client
    val serverGroup = socketPair.server
    val client = clientGroup.external
    val server = serverGroup.external
    def clientFn(): Unit = TestUtil.cannotFail {
      client.write(ByteBuffer.wrap(data))
      client.close()
      assert(clientGroup.tls.shutdownSent())
      assert(!clientGroup.tls.shutdownReceived())
      intercept[ClosedChannelException] {
        client.write(ByteBuffer.wrap(data))
      }
    }
    def serverFn(): Unit = TestUtil.cannotFail {
      val buffer = ByteBuffer.allocate(1)
      assert(server.read(buffer) == 1)
      buffer.flip()
      assert(buffer == ByteBuffer.wrap(data))
      buffer.clear()
      assert(server.read(buffer) == -1)
      assert(serverGroup.tls.shutdownReceived())
      assert(!serverGroup.tls.shutdownSent())
      // repeated
      assert(server.read(buffer) == -1)
      server.close()
      intercept[ClosedChannelException] {
        server.read(buffer)
      }
    }
    val clientThread = new Thread(() => clientFn(), "client-thread")
    val serverThread = new Thread(() => serverFn(), "server-thread")
    clientThread.start()
    serverThread.start()
    clientThread.join()
    serverThread.join()
    SocketPairFactory.checkDeallocation(socketPair)
  }

  test("TlsChannel - close and wait") {
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
      assert(clientGroup.tls.shutdownReceived())
      assert(clientGroup.tls.shutdownSent())
      intercept[ClosedChannelException] {
        client.write(ByteBuffer.wrap(data))
      }
    }
    def serverFn(): Unit = TestUtil.cannotFail {
      val buffer = ByteBuffer.allocate(1)
      assert(server.read(buffer) == 1)
      buffer.flip()
      assert(buffer == ByteBuffer.wrap(data))
      buffer.clear()
      assert(server.read(buffer) == -1)
      // repeated
      assert(server.read(buffer) == -1)
      server.close()
      assert(serverGroup.tls.shutdownReceived())
      assert(serverGroup.tls.shutdownSent())
      intercept[ClosedChannelException] {
        server.read(buffer)
      }
    }
    val clientThread = new Thread(() => clientFn(), "client-thread")
    val serverThread = new Thread(() => serverFn(), "server-thread")
    clientThread.start()
    serverThread.start()
    clientThread.join()
    serverThread.join()
    SocketPairFactory.checkDeallocation(socketPair)
  }

  test("TlsChannel - close and wait (forever)") {
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
      assert(server.read(buffer) == 1)
      buffer.flip()
      assert(buffer == ByteBuffer.wrap(data))
      buffer.clear()
      assert(server.read(buffer) == -1)
      assert(serverGroup.tls.shutdownReceived())
      assert(!serverGroup.tls.shutdownSent())
      // repeated
      assert(server.read(buffer) == -1)
    }
    val clientThread = new Thread(() => clientFn(), "client-thread")
    val serverThread = new Thread(() => serverFn(), "server-thread")
    clientThread.start()
    serverThread.start()
    clientThread.join(5000)
    serverThread.join()
    assert(clientThread.isAlive)
    serverGroup.tls.close()
    clientThread.join()
    SocketPairFactory.checkDeallocation(socketPair)
  }

  test("TlsChannel - shutdown and forget") {
    val socketPair =
      factory.nioNio(internalClientChunkSize = internalBufferSize, internalServerChunkSize = internalBufferSize)
    val clientGroup = socketPair.client
    val serverGroup = socketPair.server
    val client = clientGroup.external
    val server = serverGroup.external
    def clientFn(): Unit = TestUtil.cannotFail {
      client.write(ByteBuffer.wrap(data))
      assert(!clientGroup.tls.shutdown())
      assert(!clientGroup.tls.shutdownReceived())
      assert(clientGroup.tls.shutdownSent())
      intercept[ClosedChannelException] {
        client.write(ByteBuffer.wrap(data))
      }
    }
    def serverFn(): Unit = TestUtil.cannotFail {
      val buffer = ByteBuffer.allocate(1)
      assert(server.read(buffer) == 1)
      buffer.flip()
      assert(buffer == ByteBuffer.wrap(data))
      buffer.clear()
      assert(server.read(buffer) == -1)
      assert(serverGroup.tls.shutdownReceived())
      assert(!serverGroup.tls.shutdownSent())
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

  test("TlsChannel - shutdown and wait") {
    val socketPair =
      factory.nioNio(internalClientChunkSize = internalBufferSize, internalServerChunkSize = internalBufferSize)
    val clientGroup = socketPair.client
    val serverGroup = socketPair.server
    val client = clientGroup.external
    val server = serverGroup.external
    def clientFn(): Unit = TestUtil.cannotFail {
      client.write(ByteBuffer.wrap(data))
      // send first close_notify
      assert(!clientGroup.tls.shutdown())
      assert(!clientGroup.tls.shutdownReceived())
      assert(clientGroup.tls.shutdownSent())
      intercept[ClosedChannelException] {
        client.write(ByteBuffer.wrap(data))
      }
      // wait for second close_notify
      assert(clientGroup.tls.shutdown())
      assert(clientGroup.tls.shutdownReceived())
      assert(clientGroup.tls.shutdownSent())
    }
    def serverFn(): Unit = TestUtil.cannotFail {
      val buffer = ByteBuffer.allocate(1)
      assert(server.read(buffer) == 1)
      buffer.flip()
      assert(buffer == ByteBuffer.wrap(data))
      buffer.clear()
      assert(server.read(buffer) == -1)
      assert(serverGroup.tls.shutdownReceived())
      assert(!serverGroup.tls.shutdownSent())
      assert(server.read(buffer) == -1)
      // send second close_notify
      assert(serverGroup.tls.shutdown())
      assert(serverGroup.tls.shutdownReceived())
      assert(serverGroup.tls.shutdownSent())
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

  test("TlsChannel - shutdown and wait (forever)") {
    val socketPair =
      factory.nioNio(internalClientChunkSize = internalBufferSize, internalServerChunkSize = internalBufferSize)
    val clientGroup = socketPair.client
    val serverGroup = socketPair.server
    val client = clientGroup.external
    val server = serverGroup.external
    def clientFn(): Unit = TestUtil.cannotFail {
      client.write(ByteBuffer.wrap(data))
      // send first close_notify
      assert(!clientGroup.tls.shutdown())
      assert(!clientGroup.tls.shutdownReceived())
      assert(clientGroup.tls.shutdownSent())
      intercept[ClosedChannelException] {
        client.write(ByteBuffer.wrap(data))
      }
      // wait for second close_notify
      intercept[AsynchronousCloseException] {
        clientGroup.tls.shutdown()
      }
    }
    def serverFn(): Unit = TestUtil.cannotFail {
      val buffer = ByteBuffer.allocate(1)
      assert(server.read(buffer) == 1)
      buffer.flip()
      assert(buffer == ByteBuffer.wrap(data))
      buffer.clear()
      assert(server.read(buffer) == -1)
      assert(serverGroup.tls.shutdownReceived())
      assert(!serverGroup.tls.shutdownSent())
      assert(server.read(buffer) == -1)
      // do not send second close_notify
    }
    val clientThread = new Thread(() => clientFn(), "client-thread")
    val serverThread = new Thread(() => serverFn(), "server-thread")
    clientThread.start()
    serverThread.start()
    serverThread.join()
    clientThread.join(5000)
    assert(clientThread.isAlive)
    client.close()
    server.close()
    SocketPairFactory.checkDeallocation(socketPair)
  }

}
