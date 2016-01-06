package tlschannel

object SocketWrappers {

  val factory = new SocketPairFactory(7777)

  def nioNio(cipher: String) = {
    val ((client, _), (server, _)) = factory.nioNio(cipher)
    val clientPair = (new TlsSocketChannelWriter(client), new ByteChannelReader(client))
    val serverPair = (new TlsSocketChannelWriter(server), new ByteChannelReader(server))
    (clientPair, serverPair)
  }

  def oldNio(cipher: String) = {
    val (client, (server, _)) = factory.oldNio(cipher)
    val clientPair = (new SSLSocketWriter(client), new SocketReader(client))
    val serverPair = (new TlsSocketChannelWriter(server), new ByteChannelReader(server))
    (clientPair, serverPair)
  }

  def nioOld(cipher: String) = {
    val ((client, _), server) = factory.nioOld(cipher)
    val clientPair = (new TlsSocketChannelWriter(client), new ByteChannelReader(client))
    val serverPair = (new SSLSocketWriter(server), new SocketReader(server))
    (clientPair, serverPair)
  }

}