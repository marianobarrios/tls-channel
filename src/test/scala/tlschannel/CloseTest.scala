package tlschannel

import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException

import org.scalatest.FunSuite
import org.scalatest.Matchers

import com.typesafe.scalalogging.slf4j.StrictLogging

import tlschannel.helpers.SocketPair
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SslContextFactory
import tlschannel.helpers.TestUtil.functionToRunnable
import tlschannel.helpers.TestUtil
import java.util.concurrent.TimeUnit
import java.nio.channels.AsynchronousCloseException

class CloseTest extends FunSuite with Matchers with StrictLogging {

  val (cipher, sslContext) = SslContextFactory.standardCipher
  val factory = new SocketPairFactory(sslContext, SslContextFactory.certificateCommonName)
  val data = Array[Byte](15)

  test("SSLSocket") {
    val data = 15
    val (client, server) = factory.oldOld(cipher)
    def clientFn(): Unit = {
      val os = client.getOutputStream
      os.write(data)
      os.close()
      intercept[SocketException] {
        os.write(1)
      }
    }
    def serverFn(): Unit = {
      val is = server.getInputStream
      assert(is.read() === data)
      assert(is.read() === -1)
      // repeated
      assert(is.read() === -1)
      server.close()
      intercept[SocketException] {
        is.read()
      }
    }
    val clientThread = new Thread(() => clientFn())
    val serverThread = new Thread(() => serverFn())
    clientThread.start()
    serverThread.start()
    clientThread.join()
    serverThread.join()
  }
  
  /**
   * Less than a TLS message, to force read/write loops
   */
  val internalBufferSize = Some(10)
 
  test("TlsChannel - TCP immediate close") {
    val SocketPair(clientGroup, serverGroup) = 
      factory.nioNio(cipher, internalClientChunkSize = internalBufferSize, internalServerChunkSize = internalBufferSize)
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
      assert(server.read(buffer) === -1)
      assert(!serverGroup.tls.shutdownReceived())
      assert(!serverGroup.tls.shutdownSent())
      // repeated
      assert(server.read(buffer) === -1)
    }
    val clientThread = new Thread(() => clientFn(), "client-thread")
    val serverThread = new Thread(() => serverFn(), "server-thread")
    clientThread.start()
    serverThread.start()
    clientThread.join()
    serverThread.join()
  }
  
  test("TlsChannel - TCP close") {
    val SocketPair(clientGroup, serverGroup) = 
      factory.nioNio(cipher, internalClientChunkSize = internalBufferSize, internalServerChunkSize = internalBufferSize)
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
      assert(server.read(buffer) === 1)
      buffer.flip()
      assert(buffer === ByteBuffer.wrap(data))
      buffer.clear()
      assert(server.read(buffer) === -1)
      assert(!serverGroup.tls.shutdownReceived())
      assert(!serverGroup.tls.shutdownSent())
      // repeated
      assert(server.read(buffer) === -1)
    }
    val clientThread = new Thread(() => clientFn(), "client-thread")
    val serverThread = new Thread(() => serverFn(), "server-thread")
    clientThread.start()
    serverThread.start()
    clientThread.join()
    serverThread.join()
  }
  
  test("TlsChannel - close") {
    val SocketPair(clientGroup, serverGroup) = 
      factory.nioNio(cipher, internalClientChunkSize = internalBufferSize, internalServerChunkSize = internalBufferSize)
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
      assert(server.read(buffer) === 1)
      buffer.flip()
      assert(buffer === ByteBuffer.wrap(data))
      buffer.clear()
      assert(server.read(buffer) === -1)
      assert(serverGroup.tls.shutdownReceived())
      assert(!serverGroup.tls.shutdownSent())
      // repeated
      assert(server.read(buffer) === -1)
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
  }

  test("TlsChannel - close and wait") {
    val SocketPair(clientGroup, serverGroup) = 
      factory.nioNio(
          cipher, 
          internalClientChunkSize = internalBufferSize, 
          internalServerChunkSize = internalBufferSize, 
          waitForCloseConfirmation = true)
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
      assert(server.read(buffer) === 1)
      buffer.flip()
      assert(buffer === ByteBuffer.wrap(data))
      buffer.clear()
      assert(server.read(buffer) === -1)
      // repeated
      assert(server.read(buffer) === -1)
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
  }

  test("TlsChannel - close and wait (forever)") {
    val SocketPair(clientGroup, serverGroup) = 
      factory.nioNio(
          cipher, 
          internalClientChunkSize = internalBufferSize, 
          internalServerChunkSize = internalBufferSize, 
          waitForCloseConfirmation = true)
    val client = clientGroup.external
    val server = serverGroup.external
    def clientFn(): Unit = TestUtil.cannotFail {
      client.write(ByteBuffer.wrap(data))
      client.close()
    }
    def serverFn(): Unit = TestUtil.cannotFail {
      val buffer = ByteBuffer.allocate(1)
      assert(server.read(buffer) === 1)
      buffer.flip()
      assert(buffer === ByteBuffer.wrap(data))
      buffer.clear()
      assert(server.read(buffer) === -1)
      assert(serverGroup.tls.shutdownReceived())
      assert(!serverGroup.tls.shutdownSent())
      // repeated
      assert(server.read(buffer) === -1)
    }
    val clientThread = new Thread(() => clientFn(), "client-thread")
    val serverThread = new Thread(() => serverFn(), "server-thread")
    clientThread.start()
    serverThread.start()
    clientThread.join(3000)
    serverThread.join()
    assert(clientThread.isAlive)
  }

  test("TlsChannel - shutdown and forget") {
    val SocketPair(clientGroup, serverGroup) = 
      factory.nioNio(cipher, internalClientChunkSize = internalBufferSize, internalServerChunkSize = internalBufferSize)
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
      assert(server.read(buffer) === 1)
      buffer.flip()
      assert(buffer === ByteBuffer.wrap(data))
      buffer.clear()
      assert(server.read(buffer) === -1)
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
  }

  test("TlsChannel - shutdown and wait") {
    val SocketPair(clientGroup, serverGroup) = 
      factory.nioNio(cipher, internalClientChunkSize = internalBufferSize, internalServerChunkSize = internalBufferSize)
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
      assert(server.read(buffer) === 1)
      buffer.flip()
      assert(buffer === ByteBuffer.wrap(data))
      buffer.clear()
      assert(server.read(buffer) === -1)
      assert(serverGroup.tls.shutdownReceived())
      assert(!serverGroup.tls.shutdownSent())
      assert(server.read(buffer) === -1)
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
  }

  test("TlsChannel - shutdown and wait (forever)") {
    val SocketPair(clientGroup, serverGroup) = 
      factory.nioNio(cipher, internalClientChunkSize = internalBufferSize, internalServerChunkSize = internalBufferSize)
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
      assert(server.read(buffer) === 1)
      buffer.flip()
      assert(buffer === ByteBuffer.wrap(data))
      buffer.clear()
      assert(server.read(buffer) === -1)
      assert(serverGroup.tls.shutdownReceived())
      assert(!serverGroup.tls.shutdownSent())
      assert(server.read(buffer) === -1)
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
  }

}