package tlschannel

import javax.net.ssl.SSLEngineResult.HandshakeStatus
import java.util.concurrent.locks.ReentrantLock
import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.Status
import javax.net.ssl.SSLEngine
import java.nio.channels.ByteChannel
import java.nio.channels.SocketChannel
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLEngineResult.HandshakeStatus._
import javax.net.ssl.SSLException
import javax.net.ssl.StandardConstants
import javax.net.ssl.SSLSession
import Util.withLock

class TlsSocketChannelImpl(
  val wrapped: ByteChannel,
  val engine: SSLEngine,
  val inEncrypted: ByteBuffer,
  val initSessionCallback: SSLSession => Unit)
    extends ByteChannel {

  private val initLock = new ReentrantLock
  private val readLock = new ReentrantLock
  private val writeLock = new ReentrantLock

  @volatile
  private var initialHandshaked = false

  @volatile
  private var invalid = false

  private var tlsClosePending = false

  if (inEncrypted.capacity() < TlsSocketChannelImpl.tlsMaxRecordSize)
    throw new IllegalArgumentException(s"inEncrypted capacity must be at least ${TlsSocketChannelImpl.tlsMaxRecordSize} bytes")

  // decrypted data from inEncrypted
  private val inPlain = ByteBuffer.allocate(TlsSocketChannelImpl.tlsMaxDataSize)

  // contains data encrypted to send to the network
  private val outEncrypted = ByteBuffer.allocate(TlsSocketChannelImpl.tlsMaxRecordSize)

  // handshake wrap() method calls need a buffer to read from, even when they actually do not read anything
  private val dummyOut = ByteBuffer.allocate(0)
  dummyOut.flip() // always ready to be read (and empty)

  // read

  def read(dstBuffer: ByteBuffer): Int = {
    if (!dstBuffer.hasRemaining)
      return 0
    if (invalid)
      return -1
    if (!initialHandshaked) {
      doHandshake()
    }
    withLock(readLock) {
      while (true) {
        val transfered = transferPendingPlain(dstBuffer)
        if (transfered > 0)
          return transfered
        assert(inPlain.position == 0)
        if (tlsClosePending) {
          close()
          return -1
        }
        if (engine.getHandshakeStatus == NEED_UNWRAP || engine.getHandshakeStatus == NEED_WRAP) {
          // handshake needs both read and write locks, we already have one, take the other 
          withLock(writeLock) {
            handshakeImpl(active = false)
          }
        } else {
          try {
            unwrapLoop(statusLoopCondition = NOT_HANDSHAKING)
            while (inPlain.position == 0 && engine.getHandshakeStatus == NOT_HANDSHAKING) {
              val c = readFromNetwork() // IO block
              if (c == 0) {
                return transferPendingPlain(dstBuffer)
              }
              unwrapLoop(statusLoopCondition = NOT_HANDSHAKING)
            }
            // exit loop after we either have something to answer or the counterpart wants a handshake
          } catch {
            case e: EOFException => return -1
          }
        }
      }
    }
    throw new AssertionError
  }

  private def transferPendingPlain(dstBuffer: ByteBuffer) = {
    inPlain.flip() // will read
    val bytes = math.min(inPlain.remaining, dstBuffer.remaining)
    dstBuffer.put(inPlain.array, inPlain.position, bytes)
    inPlain.position(inPlain.position + bytes)
    inPlain.compact() // will write
    bytes
  }

  private def unwrapLoop(statusLoopCondition: HandshakeStatus): Unit = {
    inEncrypted.flip()
    var result: SSLEngineResult = null
    assert(inPlain.position == 0)
    try {
      do {
        try {
          result = engine.unwrap(inEncrypted, inPlain)
        } catch {
          case e: SSLException =>
            // something bad was received from the network, we cannot continue
            invalid = true
            throw e
        }
        if (engine.getHandshakeStatus == NEED_TASK)
          engine.getDelegatedTask.run()
        assert(engine.getHandshakeStatus != NEED_TASK)
        result.getStatus match {
          case Status.OK | Status.BUFFER_UNDERFLOW => // nothing
          case Status.BUFFER_OVERFLOW =>
            /* 
             * The engine can respond overflow even where there also is underflow (apparently the check is before).
             * Our inPlain buffer should be big enough, so an overflow should mean that everything was decrypted, and
             * more data is needed. So the inPlan must contain something.
             */
            assert(inPlain.position > 0)
          case Status.CLOSED =>
            tlsClosePending = true
            if (inPlain.position == 0)
              throw new EOFException
        }
      } while (result.getStatus == Status.OK && engine.getHandshakeStatus == statusLoopCondition)
    } finally {
      inEncrypted.compact()
    }
  }

  private def readFromNetwork(): Int = {
    assert(inEncrypted.hasRemaining)
    val res = try {
      wrapped.read(inEncrypted) // IO block
    } catch {
      case e: IOException =>
        // after a failed read, buffers can be in any state, close 
        invalid = true
        throw e
    }
    if (res == -1) {
      invalid = true
      throw new EOFException
    }
    res
  }

  // write

  def write(srcBuffer: ByteBuffer): Int = {
    if (invalid)
      throw new IOException("Socket closed")
    if (!initialHandshaked)
      doHandshake()
    val bytesToConsume = srcBuffer.remaining
    withLock(writeLock) {
      if (invalid)
        throw new IOException("Socket closed")
      var bytesConsumed = 0
      while (true) {
        if (outEncrypted.position > 0) {
          flipAndWriteToNetwork() // IO block
          if (outEncrypted.position > 0) {
            // Could not write everything, will not wrap any more
            return bytesConsumed
          }
        }
        if (bytesConsumed == bytesToConsume)
          return bytesToConsume
        val result = engine.wrap(srcBuffer, outEncrypted)
        assert(engine.getHandshakeStatus != NEED_TASK)
        result.getStatus match {
          case Status.OK =>
          case Status.BUFFER_OVERFLOW =>
            // this could happen in theory, but does not happen if outEncrypted is at least of packet size
            throw new AssertionError
          case Status.CLOSED =>
            invalid = true
            throw new IOException("Socket closed")
          case Status.BUFFER_UNDERFLOW =>
            // it does not make sense to ask more data from a client if it does not have any more
            throw new AssertionError
        }
        bytesConsumed += result.bytesConsumed
      }
      bytesConsumed
    }
  }

  private def flipAndWriteToNetwork(): Int = {
    outEncrypted.flip()
    try {
      var bytesWritten = 0
      while (outEncrypted.hasRemaining) {
        try {
          val c = writeToNetwork(outEncrypted)
          if (c == 0) {
            /*
             * If no bytes were written, it means that the socket is non-blocking and needs more buffer space, so stop 
             * the loop
             */
            return bytesWritten
          }
          bytesWritten += c
        } catch {
          case e: IOException =>
            // after a failed write, buffers can be in any state, close 
            invalid = true
            throw e
        }
        // blocking SocketChannels can write less than all the bytes just before an error the loop forces the exception
      }
      bytesWritten
    } finally {
      outEncrypted.compact()
    }
  }

  protected def writeToNetwork(out: ByteBuffer) = {
    wrapped.write(out)
  }

  // handshake and close

  /** Force new handshake */
  def renegotiate() = {
    if (!initialHandshaked)
      doHandshake()
    withLock(readLock) {
      withLock(writeLock) {
        handshakeImpl(active = true)
      }
    }
  }

  /** Process a new handshake initiated by the counter party */
  def doPassiveHandshake() = {
    if (!initialHandshaked)
      doHandshake()
    withLock(readLock) {
      withLock(writeLock) {
        handshakeImpl(active = false)
      }
    }
  }

  /** Initial handshake */
  def doHandshake(): Unit = withLock(initLock) {
    if (!initialHandshaked) {
      withLock(readLock) {
        withLock(writeLock) {
          handshakeImpl(active = true)
        }
      }
      initSessionCallback(engine.getSession)
      initialHandshaked = true
    }
  }

  private def handshakeImpl(active: Boolean): Unit = {
    assert(inPlain.position == 0)
    // write any pending bytes
    val bytesToWrite = outEncrypted.position
    if (bytesToWrite > 0) {
      val c = flipAndWriteToNetwork() // IO block
      if (c < bytesToWrite)
        throw new NeedsWriteException
    }
    if (active)
      engine.beginHandshake()
    handShakeLoop()
  }

  private def handShakeLoop(): Unit = {
    assert(inPlain.position == 0)
    try {
      while (true) {
        engine.getHandshakeStatus match {
          case NEED_WRAP =>
            assert(outEncrypted.position == 0)
            val result = engine.wrap(dummyOut, outEncrypted)
            assert(engine.getHandshakeStatus != NEED_TASK)
            assert(result.getStatus == Status.OK)
            val bytesToWrite = outEncrypted.position
            val c = flipAndWriteToNetwork() // IO block
            if (c < bytesToWrite)
              throw new NeedsWriteException
          case NEED_UNWRAP =>
            assert(inPlain.position == 0)
            unwrapLoop(statusLoopCondition = NEED_UNWRAP)
            while (engine.getHandshakeStatus == NEED_UNWRAP) {
              val bytesRead = readFromNetwork() // IO block
              if (bytesRead == 0)
                throw new NeedsReadException
              unwrapLoop(statusLoopCondition = NEED_UNWRAP)
            }
          case NOT_HANDSHAKING | FINISHED | NEED_TASK => return
        }
      }
    } catch {
      case e: TlsNonBlockingNecessityException => throw e
      case e: IOException => {
        val reason = if (e.getMessage == null || e.getMessage.isEmpty()) {
          e.getClass.getCanonicalName
        } else e.getMessage
        throw new SSLHandshakeException(s"Handshaking aborted. Reason: $reason")
      }
    }
  }

  def close(): Unit = withLock(writeLock) {
    if (!invalid) {
      engine.closeOutbound()
      if (engine.getHandshakeStatus == NEED_WRAP) {
        // close notify alert only, does not await for peer response.
        assert(outEncrypted.position == 0)
        val result = engine.wrap(dummyOut, outEncrypted)
        assert(result.getStatus == Status.CLOSED)
        try {
          flipAndWriteToNetwork() // IO block
        } catch {
          case e: Exception => // graceful close of TLS connection failed.
        }
      }
      invalid = true
    }
    Util.closeChannel(wrapped)
  }

  def isOpen() = wrapped.isOpen

}

object TlsSocketChannelImpl {

  def checkReadBuffer(in: ByteBuffer): Unit = {
    if (in == null)
      throw new NullPointerException
    if (in.isReadOnly)
      throw new IllegalArgumentException
  }

  def checkWriteBuffer(out: ByteBuffer): Unit = {
    if (out == null)
      throw new NullPointerException
  }

  val tlsMaxRecordSize =
    5 + // header
      256 + // IV
      32768 + // 2^15 bytes of data
      256 + // max padding
      20 // SHA1 hash

  val tlsMaxDataSize =
    32768 // 2^15 bytes of data

}
