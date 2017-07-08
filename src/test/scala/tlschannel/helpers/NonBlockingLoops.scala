package tlschannel.helpers

import tlschannel.NeedsWriteException
import tlschannel.NeedsReadException
import tlschannel.NeedsTaskException
import java.util.concurrent.atomic.LongAdder

import scala.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.nio.channels.Selector
import java.util.concurrent.Executors
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey

import tlschannel.helpers.TestUtil.IterableWithForany
import tlschannel.helpers.TestUtil.functionToRunnable

import org.scalatest.Matchers
import java.security.MessageDigest

object NonBlockingLoops extends Matchers {

  trait Endpoint {
    def key: SelectionKey
    def remaining: Int
  }

  case class WriterEndpoint(
      socketGroup: SocketGroup,
      var key: SelectionKey,
      var remaining: Int) extends Endpoint {
    val random = new Random(Loops.seed)
    val buffer = ByteBuffer.allocate(Loops.bufferSize)
    buffer.flip()
  }

  case class ReaderEndpoint(
      socketGroup: SocketGroup,
      var key: SelectionKey,
      var remaining: Int) extends Endpoint {
    val buffer = ByteBuffer.allocate(Loops.bufferSize)
    val digest = MessageDigest.getInstance(Loops.hashAlgorithm)
  }

  case class Report(
    selectorCycles: Int,
    needReadCount: Int,
    needWriteCount: Int,
    renegotiationCount: Int,
    asyncTasksRun: Int,
    totalAsyncTaskRunningTimeMs: Long)

  def loop(socketPairs: Seq[SocketPair], dataSize: Int, renegotiate: Boolean): Report = {

    val totalConnections = socketPairs.size
    val selector = Selector.open()
    val executor = Executors.newFixedThreadPool(Runtime.getRuntime.availableProcessors - 1)

    val readyTaskSockets = new ConcurrentLinkedQueue[Endpoint]

    val endpoints = for (SocketPair(client, server) <- socketPairs) yield {
      client.plain.configureBlocking(false)
      server.plain.configureBlocking(false)

      val clientEndpoint = WriterEndpoint(client, key = null, remaining = dataSize)
      val serverEndpoint = ReaderEndpoint(server, key = null, remaining = dataSize)

      clientEndpoint.key = client.plain.register(selector, SelectionKey.OP_WRITE, clientEndpoint)
      serverEndpoint.key = server.plain.register(selector, SelectionKey.OP_READ, serverEndpoint)
      (clientEndpoint, serverEndpoint)
    }

    val writers = endpoints.unzip._1
    val readers = endpoints.unzip._2
    val allEndpoints = writers ++ readers

    var taskCount = 0
    var needReadCount = 0
    var needWriteCount = 0
    var selectorCycles = 0
    var renegotiationCount = 0
    val maxRenegotiations = if (renegotiate) totalConnections * 2 * 20 else 0

    val random = new Random

    val totalTaskTimeMicros = new LongAdder

    val dataHash = Loops.expectedBytesHash(dataSize)

    while (allEndpoints.forany(_.remaining > 0)) {
      selectorCycles += 1
      selector.select() // block

      for (endpoint <- getSelectedEndpoints(selector) ++ TestUtil.removeAndCollect(readyTaskSockets.iterator())) {
        try {
          endpoint match {
            case writer: WriterEndpoint =>
              do {
                if (renegotiationCount < maxRenegotiations) {
                  if (random.nextBoolean()) {
                    renegotiationCount += 1
                    writer.socketGroup.tls.renegotiate()
                  }
                }
                if (!writer.buffer.hasRemaining()) {
                  writer.random.nextBytes(writer.buffer.array())
                  writer.buffer.position(0)
                  writer.buffer.limit(math.min(writer.buffer.capacity, writer.remaining))
                }
                val oldPosition = writer.buffer.position
                try {
                  val c = writer.socketGroup.external.write(writer.buffer)
                  assert(c >= 0) // the necessity of blocking is communicated with exceptions
                } finally {
                  val bytesWriten = writer.buffer.position - oldPosition
                  writer.remaining -= bytesWriten
                }
              } while (writer.remaining > 0)
            case reader: ReaderEndpoint =>
              do {
                reader.buffer.clear()
                val c = reader.socketGroup.external.read(reader.buffer)
                assert(c > 0) // the necessity of blocking is communicated with exceptions
                reader.digest.update(reader.buffer.array, 0, c)
                reader.remaining -= c
              } while (reader.remaining > 0)
          }
        } catch {
          case e: NeedsWriteException =>
            needWriteCount += 1
            endpoint.key.interestOps(SelectionKey.OP_WRITE)
          case e: NeedsReadException =>
            needReadCount += 1
            endpoint.key.interestOps(SelectionKey.OP_READ)
          case e: NeedsTaskException =>
            executor.submit { () =>
              val elapsed = TestUtil.time {
                e.getTask.run()
              }
              selector.wakeup()
              readyTaskSockets.add(endpoint)
              totalTaskTimeMicros.add(elapsed)
            }
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
      assert(dataHash === reader.digest.digest())
    }

    Report(selectorCycles, needReadCount, needWriteCount, renegotiationCount, taskCount, totalTaskTimeMicros.sum() / 1000)
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