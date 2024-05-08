package tlschannel;

import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import scala.Option;
import tlschannel.helpers.Loops;
import tlschannel.helpers.SocketGroups.SocketPair;
import tlschannel.helpers.SocketPairFactory;
import tlschannel.helpers.SocketPairFactory.ChuckSizes;
import tlschannel.helpers.SocketPairFactory.ChunkSizeConfig;
import tlschannel.helpers.SslContextFactory;
import tlschannel.util.ListUtils;
import tlschannel.util.StreamUtils;

@TestInstance(Lifecycle.PER_CLASS)
public class BlockingTest {

    private static final int dataSize = 60 * 1000;
    private final SslContextFactory sslContextFactory = new SslContextFactory();
    private final SocketPairFactory factory = new SocketPairFactory(sslContextFactory.defaultContext());

    // Test a half-duplex interaction, with renegotiation before reversing the direction of the flow (as in HTTP)
    @TestFactory
    public Collection<DynamicTest> testHalfDuplexWireRenegotiations() {
        System.out.println("testHalfDuplexWireRenegotiations():");
        List<Integer> sizes = StreamUtils.iterate(1, x -> x < SslContextFactory.tlsMaxDataSize * 2, x -> x * 2)
                .collect(Collectors.toList());
        List<Integer> reversedSizes = ListUtils.reversed(sizes);
        List<DynamicTest> ret = new ArrayList<>();
        for (int i = 0; i < sizes.size(); i++) {
            int size1 = sizes.get(i);
            int size2 = reversedSizes.get(i);
            ret.add(DynamicTest.dynamicTest(
                    String.format("testHalfDuplexWireRenegotiations() - size1=%d, size2=%d", size1, size2), () -> {
                        SocketPair socketPair = factory.nioNio(
                                Option.apply(null),
                                Option.apply(new ChunkSizeConfig(
                                        new ChuckSizes(Optional.of(size1), Optional.of(size2)),
                                        new ChuckSizes(Optional.of(size1), Optional.of(size2)))),
                                true,
                                false,
                                Option.apply(null));
                        Loops.halfDuplex(socketPair, dataSize, true, false);
                        System.out.printf("%5d -eng-> %5d -net-> %5d -eng-> %5d\n", size1, size2, size1, size2);
                    }));
        }
        return ret;
    }

    // Test a full-duplex interaction, without any renegotiation
    @TestFactory
    public Collection<DynamicTest> testFullDuplex() {
        System.out.println("testFullDuplex():");
        List<Integer> sizes = StreamUtils.iterate(1, x -> x < SslContextFactory.tlsMaxDataSize * 2, x -> x * 2)
                .collect(Collectors.toList());
        List<Integer> reversedSizes = ListUtils.reversed(sizes);
        List<DynamicTest> ret = new ArrayList<>();
        for (int i = 0; i < sizes.size(); i++) {
            int size1 = sizes.get(i);
            int size2 = reversedSizes.get(i);
            ret.add(DynamicTest.dynamicTest(
                    String.format("testFullDuplex() - size1=%d, size2=%d", size1, size2), () -> {
                        SocketPair socketPair = factory.nioNio(
                                Option.apply(null),
                                Option.apply(new ChunkSizeConfig(
                                        new ChuckSizes(Optional.of(size1), Optional.of(size2)),
                                        new ChuckSizes(Optional.of(size1), Optional.of(size2)))),
                                true,
                                false,
                                Option.apply(null));
                        Loops.fullDuplex(socketPair, dataSize);
                        System.out.printf("%5d -eng-> %5d -net-> %5d -eng-> %5d\n", size1, size2, size1, size2);
                    }));
        }
        return ret;
    }
}
