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
import scala.collection.JavaConversions._
import org.scalatest.Matchers

object NonBlockingLoops extends Matchers {

  case class Endpoint(socketGroup: SocketGroup, isClient: Boolean, buffer: ByteBuffer, var key: SelectionKey)

  case class Report(
    selectorCycles: Int,
    needReadCount: Int,
    needWriteCount: Int,
    renegotiationCount: Int,
    asyncTasksRun: Int,
    totalAsyncTaskRunningTimeMs: Long)

  def testNonBlockingLoop(pairs: Seq[SocketPair], dataSize: Int, renegotiate: Boolean): Report = {

    val masterBuffer = ByteBuffer.allocate(dataSize)
    Random.nextBytes(masterBuffer.array)

    val totalConnections = pairs.size

    val selector = Selector.open()
    val executor = Executors.newFixedThreadPool(Runtime.getRuntime.availableProcessors - 1)

    val readyTaskSockets = new ConcurrentLinkedQueue[Endpoint]

    val endpoints = for (SocketPair(client, server) <- pairs) yield {
      val originBuffer = masterBuffer.duplicate()
      val targetBuffer = ByteBuffer.allocate(dataSize)
      client.plain.configureBlocking(false)
      server.plain.configureBlocking(false)

      val clientEndpoint = Endpoint(client, true, originBuffer, key = null)
      val serverEndpoint = Endpoint(server, false, targetBuffer, key = null)

      clientEndpoint.key = client.plain.register(selector, SelectionKey.OP_WRITE, clientEndpoint)
      serverEndpoint.key = server.plain.register(selector, SelectionKey.OP_READ, serverEndpoint)
      (clientEndpoint, serverEndpoint)
    }

    val originEndpoints = endpoints.unzip._1
    val targetEndpoints = endpoints.unzip._2

    var taskCount = 0
    var needReadCount = 0
    var needWriteCount = 0
    var selectorCycles = 0
    var renegotiationCount = 0
    val maxRenegotiations = if (renegotiate) totalConnections * 2 * 20 else 0

    val random = new Random

    val totalTaskTimeMicros = new LongAdder

    while (originEndpoints.forany(_.buffer.hasRemaining) || targetEndpoints.forany(_.buffer.hasRemaining)) {
      selectorCycles += 1
      selector.select() // block

      for (endpoint <- getSelectedEndpoints(selector) ++ TestUtil.removeAndCollect(readyTaskSockets.iterator())) {
        try {
          if (endpoint.isClient) {

            while (endpoint.buffer.hasRemaining) {
              if (renegotiationCount < maxRenegotiations) {
                if (random.nextBoolean()) {
                  renegotiationCount += 1
                  endpoint.socketGroup.tls.renegotiate()
                }
              }
              val c = endpoint.socketGroup.external.write(endpoint.buffer)
              assert(c > 0) // the necessity of blocking is communicated with exceptions
            }
          } else {
            while (endpoint.buffer.hasRemaining) {
              val c = endpoint.socketGroup.external.read(endpoint.buffer)
              assert(c > 0) // the necessity of blocking is communicated with exceptions
            }
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

    for (SocketPair(client, server) <- pairs) {
      client.external.close()
      server.external.close()
    }

    for ((origin, target) <- endpoints) {
      // flip buffers before comparison, as the equals() operates only in remaining bytes
      target.buffer.flip()
      origin.buffer.flip()
      assert(target.buffer === origin.buffer)
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