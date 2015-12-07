package tlschannel

object SocketWrappers {

  val factory = new SocketPairFactory(7777)

  def plain_Old_Old() = {
    val (client, server) = factory.plain_Old_Old()
    ((new SocketWriter(client), new SocketReader(client)), (new SocketWriter(server), new SocketReader(server)))
  }

  def plain_Nio_Nio() = {
    val ((client, rawClient), (server, rawServer)) = factory.plain_Nio_Nio()
    val clientPair = (new SocketChannelWriter(client, rawClient), new ByteChannelReader(client, rawClient))
    val serverPair = (new SocketChannelWriter(server, rawServer), new ByteChannelReader(server, rawServer))
    (clientPair, serverPair)
  }

  def plain_Old_Nio() = {
    val (client, (server, rawServer)) = factory.plain_Old_Nio()
    val clientPair = (new SocketWriter(client), new SocketReader(client))
    val serverPair = (new SocketChannelWriter(server, rawServer), new ByteChannelReader(server, rawServer))
    (clientPair, serverPair)
  }

  def plain_Nio_Old() = {
    val ((client, rawClient), server) = factory.plain_Nio_Old()
    val clientPair = (new SocketChannelWriter(client, rawClient), new ByteChannelReader(client, rawClient))
    val serverPair = (new SocketWriter(server), new SocketReader(server))
    (clientPair, serverPair)
  }

  def tls_Nio_Nio(cipher: String) = {
    val ((client, rawClient), (server, rawServer)) = factory.tls_Nio_Nio(cipher)
    val clientPair = (new TlsSocketChannelWriter(client, rawClient), new ByteChannelReader(client, rawClient))
    val serverPair = (new TlsSocketChannelWriter(server, rawServer), new ByteChannelReader(server, rawServer))
    (clientPair, serverPair)
  }

  def tls_Old_Nio(cipher: String) = {
    val (client, (server, rawServer)) = factory.tls_Old_Nio(cipher)
    val clientPair = (new SocketWriter(client), new SocketReader(client))
    val serverPair = (new TlsSocketChannelWriter(server, rawServer), new ByteChannelReader(server, rawServer))
    (clientPair, serverPair)
  }

  def tls_Nio_Old(cipher: String) = {
    val ((client, rawClient), server) = factory.tls_Nio_Old(cipher)
    val clientPair = (new TlsSocketChannelWriter(client, rawClient), new ByteChannelReader(client, rawClient))
    val serverPair = (new SocketWriter(server), new SocketReader(server))
    (clientPair, serverPair)
  }

  def tls_Old_Old(cipher: String) = {
    val (client, server) = factory.tls_Old_Old(cipher)
    ((new SocketWriter(client), new SocketReader(client)), (new SocketWriter(server), new SocketReader(server)))
  }

}