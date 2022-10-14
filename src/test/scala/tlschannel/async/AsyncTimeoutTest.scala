package tlschannel.async

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.TestInstance.Lifecycle
import org.junit.jupiter.api.{Assertions, Test, TestInstance}

import java.nio.ByteBuffer
import java.nio.channels.CompletionHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.LongAdder
import tlschannel.helpers.AsyncSocketPair
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SslContextFactory

@TestInstance(Lifecycle.PER_CLASS)
class AsyncTimeoutTest extends AsyncTestBase {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)

  val bufferSize = 10

  val repetitions = 1000

  // scheduled timeout
  @Test
  def testScheduledTimeout(): Unit = {
    println("testScheduledTimeout()")
    val channelGroup = new AsynchronousTlsChannelGroup()
    val successWrites = new LongAdder
    val successReads = new LongAdder
    for (_ <- 1 to repetitions) {
      val socketPairCount = 100
      val socketPairs = factory.asyncN(null, channelGroup, socketPairCount, runTasks = true)
      val latch = new CountDownLatch(socketPairCount * 2)
      for (AsyncSocketPair(client, server) <- socketPairs) {
        val writeBuffer = ByteBuffer.allocate(bufferSize)
        val clientDone = new AtomicBoolean
        client.external.write(
          writeBuffer,
          50,
          TimeUnit.MILLISECONDS,
          null,
          new CompletionHandler[Integer, Null] {
            override def failed(exc: Throwable, attachment: Null) = {
              if (!clientDone.compareAndSet(false, true)) {
                Assertions.fail()
              }
              latch.countDown()
            }

            override def completed(result: Integer, attachment: Null) = {
              if (!clientDone.compareAndSet(false, true)) {
                Assertions.fail()
              }
              latch.countDown()
              successWrites.increment()
            }
          }
        )
        val readBuffer = ByteBuffer.allocate(bufferSize)
        val serverDone = new AtomicBoolean
        server.external.read(
          readBuffer,
          100,
          TimeUnit.MILLISECONDS,
          null,
          new CompletionHandler[Integer, Null] {
            override def failed(exc: Throwable, attachment: Null) = {
              if (!serverDone.compareAndSet(false, true)) {
                Assertions.fail()
              }
              latch.countDown()
            }

            override def completed(result: Integer, attachment: Null) = {
              if (!serverDone.compareAndSet(false, true)) {
                Assertions.fail()
              }
              latch.countDown()
              successReads.increment()
            }
          }
        )
      }
      latch.await()
      for (AsyncSocketPair(client, server) <- socketPairs) {
        client.external.close()
        server.external.close()
      }
    }

    shutdownChannelGroup(channelGroup)
    assertChannelGroupConsistency(channelGroup)

    assertEquals(0, channelGroup.getFailedReadCount)
    assertEquals(0, channelGroup.getFailedWriteCount)

    assertEquals(channelGroup.getSuccessfulWriteCount, successWrites.longValue)
    assertEquals(channelGroup.getSuccessfulReadCount, successReads.longValue)

    println(f"success writes:     ${successWrites.longValue}%8d")
    println(f"success reads:      ${successReads.longValue}%8d")
    printChannelGroupStatus(channelGroup)
  }

  // triggered timeout
  @Test
  def testTriggeredTimeout(): Unit = {
    println("testScheduledTimeout()")
    val channelGroup = new AsynchronousTlsChannelGroup()
    var successfulWriteCancellations = 0
    var successfulReadCancellations = 0
    for (_ <- 1 to repetitions) {
      val socketPairCount = 100
      val socketPairs = factory.asyncN(null, channelGroup, socketPairCount, runTasks = true)
      val futures = for (AsyncSocketPair(client, server) <- socketPairs) yield {
        val writeBuffer = ByteBuffer.allocate(bufferSize)
        val writeFuture = client.external.write(writeBuffer)
        val readBuffer = ByteBuffer.allocate(bufferSize)
        val readFuture = server.external.read(readBuffer)
        (writeFuture, readFuture)
      }

      for ((writeFuture, readFuture) <- futures) {
        if (writeFuture.cancel(true)) {
          successfulWriteCancellations += 1
        }
        if (readFuture.cancel(true)) {
          successfulReadCancellations += 1
        }
      }
      for (AsyncSocketPair(client, server) <- socketPairs) {
        client.external.close()
        server.external.close()
      }
    }
    shutdownChannelGroup(channelGroup)
    assertChannelGroupConsistency(channelGroup)

    assertEquals(0, channelGroup.getFailedReadCount)
    assertEquals(0, channelGroup.getFailedWriteCount)

    assertEquals(channelGroup.getCancelledWriteCount, successfulWriteCancellations)
    assertEquals(channelGroup.getCancelledReadCount, successfulReadCancellations)

    println(f"success writes:     ${channelGroup.getSuccessfulWriteCount}%8d")
    println(f"success reads:      ${channelGroup.getSuccessfulReadCount}%8d")
  }
}
