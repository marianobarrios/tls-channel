package tlschannel

object SocketWrappers {

  val factory = new SocketPairFactory(7777)

  def plain_Old_Old() = {
    val (client, server) = factory.plain_Old_Old()
    ((new SocketWriter(client), new SocketReader(client)), (new SocketWriter(server), new SocketReader(server)))
  }

  def plain_Nio_Nio(blockingClient: Boolean, blockingServer: Boolean) = {
    val ((client, rawClient), (server, rawServer)) = factory.plain_Nio_Nio()
    rawClient.configureBlocking(blockingClient)
    rawServer.configureBlocking(blockingServer)
    val clientPair = (new SocketChannelWriter(client, rawClient), new ByteChannelReader(client, rawClient))
    val serverPair = (new SocketChannelWriter(server, rawServer), new ByteChannelReader(server, rawServer))
    (clientPair, serverPair)
  }

  def plain_Old_Nio(blockingServer: Boolean) = {
    val (client, (server, rawServer)) = factory.plain_Old_Nio()
    rawServer.configureBlocking(blockingServer)    
    val clientPair = (new SocketWriter(client), new SocketReader(client))
    val serverPair = (new SocketChannelWriter(server, rawServer), new ByteChannelReader(server, rawServer))
    (clientPair, serverPair)
  }

  def plain_Nio_Old(blockingClient: Boolean) = {
    val ((client, rawClient), server) = factory.plain_Nio_Old()
    rawClient.configureBlocking(blockingClient)    
    val clientPair = (new SocketChannelWriter(client, rawClient), new ByteChannelReader(client, rawClient))
    val serverPair = (new SocketWriter(server), new SocketReader(server))
    (clientPair, serverPair)
  }

  def tls_Nio_Nio(cipher: String, blockingClient: Boolean, blockingServer: Boolean) = {
    val ((client, rawClient), (server, rawServer)) = factory.tls_Nio_Nio(cipher)
    rawClient.configureBlocking(blockingClient)
    rawServer.configureBlocking(blockingServer)
    val clientPair = (new TlsSocketChannelWriter(client, rawClient), new ByteChannelReader(client, rawClient))
    val serverPair = (new TlsSocketChannelWriter(server, rawServer), new ByteChannelReader(server, rawServer))
    (clientPair, serverPair)
  }

  def tls_Old_Nio(cipher: String, blockingServer: Boolean) = {
    val (client, (server, rawServer)) = factory.tls_Old_Nio(cipher)
    rawServer.configureBlocking(blockingServer)
    val clientPair = (new SocketWriter(client), new SocketReader(client))
    val serverPair = (new TlsSocketChannelWriter(server, rawServer), new ByteChannelReader(server, rawServer))
    (clientPair, serverPair)
  }

  def tls_Nio_Old(cipher: String, blockingClient: Boolean) = {
    val ((client, rawClient), server) = factory.tls_Nio_Old(cipher)
    rawClient.configureBlocking(blockingClient)    
    val clientPair = (new TlsSocketChannelWriter(client, rawClient), new ByteChannelReader(client, rawClient))
    val serverPair = (new SocketWriter(server), new SocketReader(server))
    (clientPair, serverPair)
  }

  def tls_Old_Old(cipher: String) = {
    val (client, server) = factory.tls_Old_Old(cipher)
    ((new SocketWriter(client), new SocketReader(client)), (new SocketWriter(server), new SocketReader(server)))
  }

}