package tlschannel

import javax.net.ssl.SSLContext
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import java.nio.channels.ByteChannel
import javax.net.ssl.SSLSession
import java.nio.channels.SocketChannel

class TlsClientSocketChannel(
    val wrapped: ByteChannel, 
    val engine: SSLEngine, 
    val sessionInitCallback: SSLSession => Unit = session => ()) 
  extends TlsSocketChannel {

  if (!engine.getUseClientMode)
    throw new IllegalArgumentException("SSLEngine must be in client mode")
  
  private val inBuffer = ByteBuffer.allocate(TlsSocketChannelImpl.tlsMaxRecordSize)
      
  private val impl = new TlsSocketChannelImpl(wrapped, wrapped, engine, inBuffer, sessionInitCallback)

  def getSession() = engine.getSession
  
  def read(in: ByteBuffer) = {
    TlsSocketChannelImpl.checkReadBuffer(in)
    impl.read(in)
  }
  
  def write(out: ByteBuffer) = {
    TlsSocketChannelImpl.checkWriteBuffer(out)
    impl.write(out)
  }

  def renegotiate() = impl.renegotiate()
  def doPassiveHandshake() = impl.doPassiveHandshake()
  def doHandshake() = impl.doHandshake()
  def close() = impl.close()

}