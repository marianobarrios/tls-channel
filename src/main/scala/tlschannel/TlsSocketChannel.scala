package tlschannel

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.channels.ByteChannel
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSession

trait TlsSocketChannel extends ByteChannel {

  def wrapped: ByteChannel
  def read(dstBuffer: ByteBuffer): Int
  def write(srcBuffer: ByteBuffer): Int

  def renegotiate()
  def doPassiveHandshake()
  def doHandshake(): Unit
  def close(): Unit
  
  def isOpen() = wrapped.isOpen

  def getSession(): SSLSession
  
}