package tlschannel

import com.typesafe.scalalogging.StrictLogging
import tlschannel.helpers.{Loops, SocketPairFactory, SslContextFactory}
import java.lang.management.ManagementFactory

/** Test to be run with no-op (Epsilon) GC, in order to measure GC footprint. It's in the form of a separate main method
  * in order to be run using its own VM.
  */
object AllocationTest extends StrictLogging {

  val sslContextFactory = new SslContextFactory
  val factory = new SocketPairFactory(sslContextFactory.defaultContext)
  val dataSize = 1_000_000

  /** Test a half-duplex interaction, with renegotiation before reversing the direction of the flow (as in HTTP)
    */
  def main(args: Array[String]): Unit = {

    val memoryBean = ManagementFactory.getMemoryMXBean

    val socketPair1 = factory.nioNio()
    val socketPair2 = factory.nioNio()
    val socketPair3 = factory.nioNio()

    // do a "warm-up" loop, in order to not count anything statically allocated
    Loops.halfDuplex(socketPair1, 10000)

    val before = memoryBean.getHeapMemoryUsage.getUsed
    Loops.halfDuplex(socketPair2, dataSize)
    Loops.halfDuplex(socketPair3, dataSize, scattering = true)
    val after = memoryBean.getHeapMemoryUsage.getUsed

    println(f"memory allocation test finished - used heap: ${(after - before).toDouble / 1024}%.0f KB")
  }

}
