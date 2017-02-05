package tlschannel.helpers

import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult.HandshakeStatus
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.Status
import com.typesafe.scalalogging.slf4j.StrictLogging
import tlschannel.ByteBufferSet

/*
 * "Null" {@link SSLEngine} that does nothing to the bytes.
 */
class NullSslEngine extends SSLEngine with StrictLogging {

  /**
   * Internal buffers are still used to prevent any underlying optimization of
   * the wrap/unwrap.
   */
  val bufferSize = 16000
  
  def beginHandshake() = {}
  def closeInbound() = {}
  def closeOutbound() = {}
  def getDelegatedTask() = null
  def getEnableSessionCreation() = true
  def getEnabledCipherSuites() = Array()
  def getEnabledProtocols() = Array()
  def getHandshakeStatus() = HandshakeStatus.NOT_HANDSHAKING
  def getNeedClientAuth() = false
  def getSession() = new NullSslSession(bufferSize)
  def getSupportedCipherSuites() = Array()
  def getSupportedProtocols() = Array()
  def getUseClientMode() = true
  def getWantClientAuth() = false
  def isInboundDone() = false
  def isOutboundDone() = false
  def setEnableSessionCreation(b: Boolean) = {}
  def setEnabledCipherSuites(a: Array[String]) = {}
  def setEnabledProtocols(a: Array[String]) = {}
  def setNeedClientAuth(b: Boolean) = {}
  def setUseClientMode(b: Boolean) = {}
  def setWantClientAuth(b: Boolean) = {}

  val unwrapBuffer = ByteBuffer.allocate(bufferSize)
  val wrapBuffer = ByteBuffer.allocate(bufferSize)

  def unwrap(src: ByteBuffer, dsts: Array[ByteBuffer], offset: Int, length: Int): SSLEngineResult = {
    val srcSet = new ByteBufferSet(src)
    val dstSet = new ByteBufferSet(dsts, offset, length)
    if (!srcSet.hasRemaining)
      return new SSLEngineResult(Status.BUFFER_UNDERFLOW, HandshakeStatus.NOT_HANDSHAKING, 0, 0)
    val unwrapSize = math.min(unwrapBuffer.capacity, src.remaining).asInstanceOf[Int]
    if (dstSet.remaining < unwrapSize)
      return new SSLEngineResult(Status.BUFFER_OVERFLOW, HandshakeStatus.NOT_HANDSHAKING, 0, 0)
    unwrapBuffer.clear()
    srcSet.get(unwrapBuffer, unwrapSize)
    unwrapBuffer.flip()
    dstSet.put(unwrapBuffer, unwrapSize)
    return new SSLEngineResult(Status.OK, HandshakeStatus.NOT_HANDSHAKING, unwrapSize, unwrapSize)
  }

  def wrap(srcs: Array[ByteBuffer], offset: Int, length: Int, dst: ByteBuffer): SSLEngineResult = {
    val srcSet = new ByteBufferSet(srcs, offset, length)
    if (!srcSet.hasRemaining)
      return new SSLEngineResult(Status.OK, HandshakeStatus.NOT_HANDSHAKING, 0, 0)
    val wrapSize = math.min(wrapBuffer.capacity, srcSet.remaining).asInstanceOf[Int]
    if (dst.remaining < wrapSize)
      return new SSLEngineResult(Status.BUFFER_OVERFLOW, HandshakeStatus.NOT_HANDSHAKING, 0, 0)
    wrapBuffer.clear()
    srcSet.get(wrapBuffer, wrapSize)
    wrapBuffer.flip()
    dst.put(wrapBuffer)
    return new SSLEngineResult(Status.OK, HandshakeStatus.NOT_HANDSHAKING, wrapSize, wrapSize)
  }

}