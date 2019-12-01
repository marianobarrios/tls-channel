package tlschannel.helpers

import java.nio.ByteBuffer
import java.nio.channels.CompletionHandler
import java.security.MessageDigest
import java.util.SplittableRandom
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder

import org.scalatest.Assertions

import scala.util.control.Breaks

object AsyncLoops extends Assertions {

  trait Endpoint {
    def remaining: Int
    def exception: Option[Throwable]
  }

  case class WriterEndpoint(socketGroup: AsyncSocketGroup, var remaining: Int) extends Endpoint {
    var exception: Option[Throwable] = None
    val random = new SplittableRandom(Loops.seed)
    val buffer = ByteBuffer.allocate(Loops.bufferSize)
    buffer.flip()
  }

  case class ReaderEndpoint(socketGroup: AsyncSocketGroup, var remaining: Int) extends Endpoint {
    var exception: Option[Throwable] = None
    val buffer = ByteBuffer.allocate(Loops.bufferSize)
    val digest = MessageDigest.getInstance(Loops.hashAlgorithm)
  }

  case class Report(
    dequeueCycles: Long,
    completedReads: Long,
    failedReads: Long,
    completedWrites: Long,
    failedWrites: Long,
   )

  def loop(socketPairs: Seq[AsyncSocketPair], dataSize: Int): Report = {

    var dequeueCycles = 0
    val completedReads = new LongAdder
    val failedReads = new LongAdder
    val completedWrites = new LongAdder
    val failedWrites = new LongAdder

    val endpointQueue = new LinkedBlockingQueue[Endpoint]
    val dataHash = Loops.expectedBytesHash(dataSize)
    val endpoints = for (AsyncSocketPair(client, server) <- socketPairs) yield {
      val clientEndpoint = WriterEndpoint(client, remaining = dataSize)
      val serverEndpoint = ReaderEndpoint(server, remaining = dataSize)
      (clientEndpoint, serverEndpoint)
    }

    val (writers, readers) = endpoints.unzip
    val allEndpoints = writers ++ readers

    for (endpoint <- allEndpoints) {
      endpointQueue.put(endpoint)
    }
    var endpointsFinished = 0
    val totalEndpoints = endpoints.length * 2
    Breaks.breakable {
      while (true) {
        val endpoint = endpointQueue.take() // blocks

        dequeueCycles += 1
        endpoint.exception.foreach(throw _)
        if (endpoint.remaining == 0) {
          endpointsFinished += 1
          if (endpointsFinished == totalEndpoints) {
            Breaks.break()
          }
        } else {
          endpoint match {
            case writer: WriterEndpoint =>
              if (!writer.buffer.hasRemaining) {
                TestUtil.nextBytes(writer.random, writer.buffer.array())
                writer.buffer.position(0)
                writer.buffer.limit(math.min(writer.buffer.capacity, writer.remaining))
              }
              writer.socketGroup.external.write(writer.buffer, 1, TimeUnit.DAYS, null, new CompletionHandler[Integer, Null] {
                override def completed(c: Integer, attach: Null) = {
                  assert(c > 0)
                  writer.remaining -= c
                  endpointQueue.put(writer)
                  completedWrites.increment()
                }

                override def failed(e: Throwable, attach: Null) = {
                  writer.exception = Some(e)
                  endpointQueue.put(writer)
                  failedWrites.increment()
                }
              })
            case reader: ReaderEndpoint =>
              reader.buffer.clear()
              reader.socketGroup.external.read(reader.buffer, 1, TimeUnit.DAYS, null, new CompletionHandler[Integer, Null] {
                override def completed(c: Integer, attach: Null) = {
                  assert(c > 0)
                  reader.digest.update(reader.buffer.array, 0, c)
                  reader.remaining -= c
                  endpointQueue.put(reader)
                  completedReads.increment()
                }
                override def failed(e: Throwable, attach: Null) = {
                  reader.exception = Some(e)
                  endpointQueue.put(reader)
                  failedReads.increment()
                }
              })
          }
        }
      }
    }
    for (socketPair <- socketPairs) {
      socketPair.client.external.close()
      socketPair.server.external.close()
      SocketPairFactory.checkDeallocation(socketPair)
    }
    for (reader <- readers) {
      assert(dataHash sameElements reader.digest.digest())
    }
    Report(
      dequeueCycles,
      completedReads.longValue(),
      failedReads.longValue(),
      completedWrites.longValue(),
      failedWrites.longValue(),
    )
  }

}
