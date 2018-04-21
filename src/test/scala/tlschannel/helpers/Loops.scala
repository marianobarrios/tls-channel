package tlschannel.helpers

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.SplittableRandom

import org.scalatest.Matchers
import com.typesafe.scalalogging.StrictLogging
import tlschannel.helpers.TestUtil.Memo

object Loops extends Matchers with StrictLogging {

  val seed = 143000953L

  /*
   * Note that it is necessary to use a multiple of 4 as buffer size for writing. 
   * This is because the bytesProduced to write are generated using Random.nextBytes, that
   * always consumes full (4 byte) integers. A multiple of 4 then prevents "holes" 
   * in the random sequence.
   */
  val bufferSize = 4 * 5000

  val renegotiatePeriod = 10000
  val hashAlgorithm = "MD5" // for speed

  /**
   * Test a half-duplex interaction, with (optional) renegotiation
   * before reversing the direction of the flow (as in HTTP)
   */
  def halfDuplex(socketPair: SocketPair, dataSize: Int, renegotiation: Boolean = false, scattering: Boolean = false) = {
    val clientWriterThread = new Thread(
        () => Loops.writerLoop(dataSize, socketPair.client, renegotiation, scattering), "client-writer")
    val serverReaderThread = new Thread(
        () => Loops.readerLoop(dataSize, socketPair.server, scattering), "server-reader")
    val serverWriterThread = new Thread(
        () => Loops.writerLoop(dataSize, socketPair.server, renegotiation, scattering, shutdown = true, close = true), "server-writer")
    val clientReaderThread = new Thread(
        () => Loops.readerLoop(dataSize, socketPair.client, scattering, close = true, readEof = true), "client-reader")
    Seq(serverReaderThread, clientWriterThread).foreach(_.start())
    Seq(serverReaderThread, clientWriterThread).foreach(_.join())
    Seq(clientReaderThread, serverWriterThread).foreach(_.start())
    Seq(clientReaderThread, serverWriterThread).foreach(_.join())
    SocketPairFactory.checkDeallocation(socketPair)
  }

  def fullDuplex(socketPair: SocketPair, dataSize: Int) = {
    val clientWriterThread = new Thread(() => Loops.writerLoop(dataSize, socketPair.client), "client-writer")
    val serverWriterThread = new Thread(() => Loops.writerLoop(dataSize, socketPair.server), "server-write")
    val clientReaderThread = new Thread(() => Loops.readerLoop(dataSize, socketPair.client), "client-reader")
    val serverReaderThread = new Thread(() => Loops.readerLoop(dataSize, socketPair.server), "server-reader")
    Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.start())
    Seq(serverReaderThread, clientWriterThread, clientReaderThread, serverWriterThread).foreach(_.join())
    socketPair.client.external.close()
    socketPair.server.external.close()
    SocketPairFactory.checkDeallocation(socketPair)
  }

  def writerLoop(
    size: Int,
    socketGroup: SocketGroup,
    renegotiate: Boolean = false,
    scattering: Boolean = false,
    shutdown: Boolean = false,
    close: Boolean = false): Unit = TestUtil.cannotFail {

    logger.debug(s"Starting writer loop, size: $size, scattering: $scattering, renegotiate:$renegotiate")
    val random = new SplittableRandom(seed)
    var bytesSinceRenegotiation = 0
    var bytesRemaining = size
    val bufferArray = Array.ofDim[Byte](bufferSize)
    while (bytesRemaining > 0) {
      val buffer = ByteBuffer.wrap(bufferArray, 0, math.min(bufferSize, bytesRemaining))
      TestUtil.nextBytes(random, buffer.array)
      while (buffer.hasRemaining) {
        if (renegotiate && bytesSinceRenegotiation > renegotiatePeriod) {
          socketGroup.tls.renegotiate()
          bytesSinceRenegotiation = 0
        }
        val c = if (scattering)
          socketGroup.tls.write(multiWrap(buffer)).toInt
        else
          socketGroup.external.write(buffer)
        assert(c > 0, "blocking write must return a positive number")
        bytesSinceRenegotiation += c
        bytesRemaining -= c.toInt
        assert(bytesRemaining >= 0)
      }
    }
    if (shutdown)
      socketGroup.tls.shutdown()
    if (close)
      socketGroup.external.close()
    logger.debug("Finalizing writer loop")
  }

  def readerLoop(
    size: Int,
    socketGroup: SocketGroup,
    gathering: Boolean = false,
    readEof: Boolean = false,
    close: Boolean = false): Unit = TestUtil.cannotFail {

    logger.debug(s"Starting reader loop. Size: $size, gathering: $gathering")
    val readArray = Array.ofDim[Byte](bufferSize)
    var bytesRemaining = size
    val digest = MessageDigest.getInstance(hashAlgorithm)
    while (bytesRemaining > 0) {
      val readBuffer = ByteBuffer.wrap(readArray, 0, math.min(bufferSize, bytesRemaining))
      val c = if (gathering)
        socketGroup.tls.read(multiWrap(readBuffer)).toInt
      else
        socketGroup.external.read(readBuffer)
      assert(c > 0, "blocking read must return a positive number")
      digest.update(readBuffer.array(), 0, readBuffer.position())
      bytesRemaining -= c
      assert(bytesRemaining >= 0)
      //logger.debug(s"read $c, remaining: $bytesRemaining")
    }
    if (readEof)
      assert(socketGroup.external.read(ByteBuffer.wrap(readArray)) === -1)
    val actual = digest.digest()
    assert(actual === expectedBytesHash(size))
    if (close)
      socketGroup.external.close()
    logger.debug("Finalizing reader loop")
  }

  val expectedBytesHash = Memo { (size: Int) =>
    val digest = MessageDigest.getInstance(hashAlgorithm)
    val random = new SplittableRandom(seed)
    var generated = 0
    val bufferSize = 4 * 1024
    val array = Array.ofDim[Byte](bufferSize)
    while (generated < size) {
      TestUtil.nextBytes(random, array)
      val pending = size - generated
      digest.update(array, 0, math.min(bufferSize, pending))
      generated += bufferSize
    }
    digest.digest()
  }

  private def multiWrap(buffer: ByteBuffer) = {
    Array(ByteBuffer.allocate(0), buffer, ByteBuffer.allocate(0))
  }

}