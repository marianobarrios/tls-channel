package tlschannel

import javax.net.ssl.SSLContext
import java.nio.ByteBuffer
import java.io.EOFException
import javax.net.ssl.SSLException
import javax.net.ssl.StandardConstants
import javax.net.ssl.SNIHostName
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLEngine
import java.nio.channels.ReadableByteChannel
import java.nio.channels.ByteChannel
import javax.net.ssl.SSLSession
import Util.withLock
import java.nio.channels.SocketChannel

class TlsServerSocketChannel(
  val wrapped: ByteChannel,
  val contextFactory: Option[String] => SSLContext,
  val engineConfigurator: SSLEngine => Unit = engine => (),
  val sessionInitCallback: SSLSession => Unit = session => ())
  extends TlsSocketChannel {

  private val initLock = new ReentrantLock

  @volatile
  private var sniRead = false

  private val buffer = ByteBuffer.allocate(TlsSocketChannelImpl.tlsMaxRecordSize)

  private var impl: TlsSocketChannelImpl = null

  def getSession() = if (impl == null) null else impl.engine.getSession

  def read(in: ByteBuffer) = {
    TlsSocketChannelImpl.checkReadBuffer(in)
    if (!sniRead)
      initEngine()
    impl.read(in)
  }

  def write(in: ByteBuffer) = {
    if (!sniRead)
      initEngine()
    impl.write(in)
  }

  def renegotiate() = {
    if (!sniRead)
      initEngine()
    impl.renegotiate()
  }

  def doPassiveHandshake() = {
    if (!sniRead)
      initEngine()
    impl.doPassiveHandshake()
  }

  def doHandshake() = {
    if (!sniRead)
      initEngine()
    impl.doHandshake()
  }

  def close() = {
    if (impl != null)
      impl.close()
    Util.closeChannel(wrapped)
  }

  def initEngine() = withLock(initLock) {
    if (!sniRead) {
      val nameOpt = getServerNameIndication(wrapped, buffer) // IO block
      val sslContext = contextFactory(nameOpt) // call client code
      val engine = sslContext.createSSLEngine()
      engineConfigurator(engine) // call client code
      engine.setUseClientMode(false)
      impl = new TlsSocketChannelImpl(wrapped, wrapped, engine, buffer, sessionInitCallback)
      sniRead = true
    }
  }

  private var recordHeaderSize: Option[Int] = None

  private def getServerNameIndication(channel: ReadableByteChannel, buffer: ByteBuffer): Option[String] = {
    if (recordHeaderSize.isEmpty)
      recordHeaderSize = Some(readRecordHeaderSize(channel, buffer))
    while (buffer.position < recordHeaderSize.get) {
      readFromNetwork(channel, buffer) // IO block
    }
    buffer.flip()
    val serverNames = TlsExplorer.explore(buffer)
    buffer.compact()
    serverNames.get(StandardConstants.SNI_HOST_NAME) match {
      case hostName: SNIHostName => Some(hostName.getAsciiName)
      case _ => None // null values match here
    }
  }

  private def readRecordHeaderSize(channel: ReadableByteChannel, buffer: ByteBuffer): Int = {
    while (buffer.position < TlsExplorer.RECORD_HEADER_SIZE) {
      readFromNetwork(channel, buffer) // IO block
    }
    buffer.flip()
    val recordHeaderSize = TlsExplorer.getRequiredSize(buffer)
    if (recordHeaderSize > TlsSocketChannelImpl.tlsMaxRecordSize)
      throw new SSLException("record size too big: " + recordHeaderSize)
    buffer.compact()
    recordHeaderSize
  }

  private def readFromNetwork(channel: ReadableByteChannel, buffer: ByteBuffer): Int = {
    val n = channel.read(buffer) // IO block
    if (n == -1)
      throw new EOFException
    if (n == 0) {
      // This can only happen if the socket is non-blocking
      throw new NeedsReadException
    }
    n
  }

}