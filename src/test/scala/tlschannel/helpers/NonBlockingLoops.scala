package tlschannel.helpers

import org.junit.jupiter.api.Assertions.{assertArrayEquals, assertTrue}
import tlschannel.NeedsWriteException
import tlschannel.NeedsReadException
import tlschannel.NeedsTaskException
import tlschannel.helpers.SocketGroups.{SocketGroup, SocketPair}

import java.util.concurrent.atomic.LongAdder
import scala.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.nio.channels.Selector
import java.util.concurrent.Executors
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.security.MessageDigest
import java.time.Duration
import java.util.SplittableRandom

object NonBlockingLoops {

  trait Endpoint {
    def key: SelectionKey
    def remaining: Int
  }

  case class WriterEndpoint(socketGroup: SocketGroup, var key: SelectionKey, var remaining: Int) extends Endpoint {
    val random = new SplittableRandom(Loops.seed)
    val buffer = ByteBuffer.allocate(Loops.bufferSize)
    buffer.flip()
  }

  case class ReaderEndpoint(socketGroup: SocketGroup, var key: SelectionKey, var remaining: Int) extends Endpoint {
    val buffer = ByteBuffer.allocate(Loops.bufferSize)
    val digest = MessageDigest.getInstance(Loops.hashAlgorithm)
  }

  case class Report(
      selectorCycles: Int,
      needReadCount: Int,
      needWriteCount: Int,
      renegotiationCount: Int,
      asyncTasksRun: Int,
      totalAsyncTaskRunningTime: Duration
  ) {

    def print() = {
      println(s"Selector cycles: $selectorCycles")
      println(s"NeedRead count: $needReadCount")
      println(s"NeedWrite count: $needWriteCount")
      println(s"Renegotiation count: $renegotiationCount")
      println(s"Asynchronous tasks run: $asyncTasksRun")
      println(s"Total asynchronous task running time: ${totalAsyncTaskRunningTime.toMillis} ms")
    }
  }

  def loop(socketPairs: Seq[SocketPair], dataSize: Int, renegotiate: Boolean): Report = {

    val totalConnections = socketPairs.size
    val selector = Selector.open()
    val executor = Executors.newFixedThreadPool(Runtime.getRuntime.availableProcessors - 1)

    val readyTaskSockets = new ConcurrentLinkedQueue[Endpoint]

    val endpoints = for (pair <- socketPairs) yield {
      pair.client.plain.configureBlocking(false)
      pair.server.plain.configureBlocking(false)

      val clientEndpoint = WriterEndpoint(pair.client, key = null, remaining = dataSize)
      val serverEndpoint = ReaderEndpoint(pair.server, key = null, remaining = dataSize)

      clientEndpoint.key = pair.client.plain.register(selector, SelectionKey.OP_WRITE, clientEndpoint)
      serverEndpoint.key = pair.server.plain.register(selector, SelectionKey.OP_READ, serverEndpoint)
      (clientEndpoint, serverEndpoint)
    }

    val (writers, readers) = endpoints.unzip
    val allEndpoints = writers ++ readers

    var taskCount = 0
    var needReadCount = 0
    var needWriteCount = 0
    var selectorCycles = 0
    var renegotiationCount = 0
    val maxRenegotiations = if (renegotiate) totalConnections * 2 * 20 else 0

    val random = new Random

    val totalTaskTimeNanos = new LongAdder

    val dataHash = Loops.expectedBytesHash(dataSize)

    while (allEndpoints.exists(_.remaining > 0)) {
      selectorCycles += 1
      selector.select() // block

      for (endpoint <- getSelectedEndpoints(selector) ++ TestUtil.removeAndCollect(readyTaskSockets.iterator())) {
        try {
          endpoint match {
            case writer: WriterEndpoint =>
              // rewriting do-while loop in a way compatible with Scala 23
              while {
                if (renegotiationCount < maxRenegotiations) {
                  if (random.nextBoolean()) {
                    renegotiationCount += 1
                    writer.socketGroup.tls.renegotiate()
                  }
                }
                if (!writer.buffer.hasRemaining) {
                  TestUtil.nextBytes(writer.random, writer.buffer.array())
                  writer.buffer.position(0)
                  writer.buffer.limit(math.min(writer.buffer.capacity, writer.remaining))
                }
                val oldPosition = writer.buffer.position()
                try {
                  val c = writer.socketGroup.external.write(writer.buffer)
                  assertTrue(c >= 0) // the necessity of blocking is communicated with exceptions
                } finally {
                  val bytesWriten = writer.buffer.position() - oldPosition
                  writer.remaining -= bytesWriten
                }
                writer.remaining > 0
              } do ()
            case reader: ReaderEndpoint =>
              // rewriting do-while loop in a way compatible with Scala 23
              while {
                reader.buffer.clear()
                val c = reader.socketGroup.external.read(reader.buffer)
                assertTrue(c > 0) // the necessity of blocking is communicated with exceptions
                reader.digest.update(reader.buffer.array, 0, c)
                reader.remaining -= c
                reader.remaining > 0
              } do ()
          }
        } catch {
          case e: NeedsWriteException =>
            needWriteCount += 1
            endpoint.key.interestOps(SelectionKey.OP_WRITE)
          case e: NeedsReadException =>
            needReadCount += 1
            endpoint.key.interestOps(SelectionKey.OP_READ)
          case e: NeedsTaskException =>
            val r: Runnable = { () =>
              val start = System.nanoTime()
              e.getTask.run()
              val elapsed = Duration.ofNanos(System.nanoTime() - start)
              selector.wakeup()
              readyTaskSockets.add(endpoint)
              totalTaskTimeNanos.add(elapsed.toNanos)
            }
            executor.submit(r)
            taskCount += 1
        }
      }
    }

    for (socketPair <- socketPairs) {
      socketPair.client.external.close()
      socketPair.server.external.close()
      SocketPairFactory.checkDeallocation(socketPair)
    }

    for (reader <- readers) {
      assertArrayEquals(reader.digest.digest(), dataHash)
    }

    Report(
      selectorCycles,
      needReadCount,
      needWriteCount,
      renegotiationCount,
      taskCount,
      Duration.ofNanos(totalTaskTimeNanos.longValue())
    )
  }

  def getSelectedEndpoints(selector: Selector): Seq[Endpoint] = {
    val builder = Seq.newBuilder[Endpoint]
    val it = selector.selectedKeys().iterator()
    while (it.hasNext) {
      val key = it.next()
      key.interestOps(0) // delete all operations
      builder += key.attachment.asInstanceOf[Endpoint]
      it.remove()
    }
    builder.result()
  }

}
