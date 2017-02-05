package tlschannel.helpers

import javax.net.ssl.SSLSession

class NullSslSession(bufferSize: Int) extends SSLSession {

  def getApplicationBufferSize() = bufferSize
  def getCipherSuite() = null
  def getCreationTime() = System.currentTimeMillis()
  def getId() = Array()
  def getLastAccessedTime() = System.currentTimeMillis()
  def getLocalCertificates() = Array()
  def getLocalPrincipal() = null
  def getPacketBufferSize() = bufferSize
  def getPeerCertificateChain() = Array()
  def getPeerCertificates() = Array()
  def getPeerHost() = null
  def getPeerPort() = 0
  def getPeerPrincipal() = null
  def getProtocol() = null
  def getSessionContext() = null
  def getValue(s: String) = null
  def getValueNames() = Array()
  def invalidate() = {}
  def isValid() = true
  def putValue(k: String, v: Any) = {}
  def removeValue(k: String) = {}

}