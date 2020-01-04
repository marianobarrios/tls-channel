package tlschannel.async

import java.nio.ByteBuffer
import java.nio.channels.CompletionHandler
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.LongAdder

import org.junit.runner.RunWith
import org.scalatest.Assertions
import org.scalatest.funsuite.AnyFunSuite
import org.scalatestplus.junit.JUnitRunner
import tlschannel.helpers.AsyncSocketPair
import tlschannel.helpers.SocketPairFactory
import tlschannel.helpers.SslContextFactory

@RunWith(classOf[JUnitRunner])
class AsyncTimeoutTest extends AnyFunSuite with AsyncTestBase with Assertions {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)

  val bufferSize = 10

  test("scheduled timeout") {
    val channelGroup = new AsynchronousTlsChannelGroup()
    val socketPairCount = 1000
    val socketPairs = factory.asyncN(null, channelGroup, socketPairCount, runTasks = true)
    val latch = new CountDownLatch(socketPairCount * 2)
    val successWrites = new LongAdder
    val successReads = new LongAdder
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
              fail()
            }
            latch.countDown()
          }
          override def completed(result: Integer, attachment: Null) = {
            if (!clientDone.compareAndSet(false, true)) {
              fail()
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
              fail()
            }
            latch.countDown()
          }
          override def completed(result: Integer, attachment: Null) = {
            if (!serverDone.compareAndSet(false, true)) {
              fail()
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

    shutdownChannelGroup(channelGroup)
    assertChannelGroupConsistency(channelGroup)

    assert(channelGroup.getFailedReadCount == 0)
    assert(channelGroup.getFailedWriteCount == 0)

    assert(successWrites.longValue == channelGroup.getSuccessfulWriteCount)
    assert(successReads.longValue == channelGroup.getSuccessfulReadCount)

    info(f"success writes:     ${successWrites.longValue}%8d")
    info(f"success reads:      ${successReads.longValue}%8d")
    printChannelGroupStatus(channelGroup)
  }

  test("triggered timeout") {
    val channelGroup = new AsynchronousTlsChannelGroup()
    val socketPairCount = 1000
    val socketPairs = factory.asyncN(null, channelGroup, socketPairCount, runTasks = true)
    val futures = for (AsyncSocketPair(client, server) <- socketPairs) yield {
      val writeBuffer = ByteBuffer.allocate(bufferSize)
      val writeFuture = client.external.write(writeBuffer)
      val readBuffer = ByteBuffer.allocate(bufferSize)
      val readFuture = server.external.read(readBuffer)
      (writeFuture, readFuture)
    }
    var successfulWriteCancellations = 0
    var successfulReadCancellations = 0
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

    shutdownChannelGroup(channelGroup)
    assertChannelGroupConsistency(channelGroup)

    assert(channelGroup.getFailedReadCount == 0)
    assert(channelGroup.getFailedWriteCount == 0)

    assert(successfulWriteCancellations == channelGroup.getCancelledWriteCount)
    assert(successfulReadCancellations == channelGroup.getCancelledReadCount)

    info(f"success writes:     ${channelGroup.getSuccessfulWriteCount}%8d")
    info(f"success reads:      ${channelGroup.getSuccessfulReadCount}%8d")
    printChannelGroupStatus(channelGroup)
  }

}
