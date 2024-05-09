package tlschannel;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.Optional;
import tlschannel.helpers.Loops;
import tlschannel.helpers.SocketGroups.SocketPair;
import tlschannel.helpers.SocketPairFactory;
import tlschannel.helpers.SslContextFactory;

/**
 * Test to be run with no-op (Epsilon) GC, in order to measure GC footprint. It's in the form of a separate main method
 * in order to be run using its own VM.
 */
class AllocationTest {

    private static final SslContextFactory sslContextFactory = new SslContextFactory();
    private static final SocketPairFactory factory = new SocketPairFactory(sslContextFactory.defaultContext());
    private static final int dataSize = 1_000_000;

    /** Test a half-duplex interaction, with renegotiation before reversing the direction of the flow (as in HTTP)
     */
    public static void main(String[] args) {

        MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();

        SocketPair socketPair1 = factory.nioNio(Optional.empty(), Optional.empty(), true, false, Optional.empty());
        SocketPair socketPair2 = factory.nioNio(Optional.empty(), Optional.empty(), true, false, Optional.empty());
        SocketPair socketPair3 = factory.nioNio(Optional.empty(), Optional.empty(), true, false, Optional.empty());

        // do a "warm-up" loop, in order to not count anything statically allocated
        Loops.halfDuplex(socketPair1, 10000, false, false);

        long before = memoryBean.getHeapMemoryUsage().getUsed();
        Loops.halfDuplex(socketPair2, dataSize, false, false);
        Loops.halfDuplex(socketPair3, dataSize, true, false);
        long after = memoryBean.getHeapMemoryUsage().getUsed();

        System.out.printf("memory allocation test finished - used heap: %.0f KB\n", (after - before) / 1024.0);
    }
}
