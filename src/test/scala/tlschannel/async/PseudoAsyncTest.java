package tlschannel.async;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import scala.Option;
import tlschannel.helpers.Loops;
import tlschannel.helpers.SocketGroups;
import tlschannel.helpers.SocketPairFactory;
import tlschannel.helpers.SocketPairFactory.ChuckSizes;
import tlschannel.helpers.SocketPairFactory.ChunkSizeConfig;
import tlschannel.helpers.SslContextFactory;
import tlschannel.util.ListUtils;
import tlschannel.util.StreamUtils;

@TestInstance(Lifecycle.PER_CLASS)
public class PseudoAsyncTest {

    private final SslContextFactory sslContextFactory = new SslContextFactory();

    private final AsynchronousTlsChannelGroup channelGroup = new AsynchronousTlsChannelGroup();
    // TODO: close
    private final SocketPairFactory factory = new SocketPairFactory(sslContextFactory.defaultContext());
    private final int dataSize = 60 * 1000;

    // test a half-duplex interaction, with renegotiation before reversing the direction of the flow (as in HTTP)
    @TestFactory
    public Collection<DynamicTest> testHalfDuplex() {
        List<Integer> sizes = StreamUtils.iterate(1, x -> x < SslContextFactory.tlsMaxDataSize * 2, x -> x * 2)
                .collect(Collectors.toList());
        List<Integer> reversedSizes = ListUtils.reversed(sizes);
        List<DynamicTest> ret = new ArrayList<>();
        for (int i = 0; i < sizes.size(); i++) {
            int size1 = sizes.get(i);
            int size2 = reversedSizes.get(i);
            ret.add(DynamicTest.dynamicTest(
                    String.format("testHalfDuplex() - size1=%s, size2=%s", size1, size2), () -> {
                        SocketGroups.SocketPair socketPair = factory.nioNio(
                                Option.apply(null),
                                Option.apply(new ChunkSizeConfig(
                                        new ChuckSizes(Optional.of(size1), Optional.of(size2)),
                                        new ChuckSizes(Optional.of(size1), Optional.of(size2)))),
                                true,
                                false,
                                Option.apply(channelGroup));
                        Loops.halfDuplex(socketPair, dataSize, false, false);
                    }));
        }
        return ret;
    }

    // test a full-duplex interaction, without any renegotiation
    @TestFactory
    public Collection<DynamicTest> testFullDuplex() {
        List<Integer> sizes = StreamUtils.iterate(1, x -> x < SslContextFactory.tlsMaxDataSize * 2, x -> x * 2)
                .collect(Collectors.toList());
        List<Integer> reversedSizes = ListUtils.reversed(sizes);
        List<DynamicTest> ret = new ArrayList<>();
        for (int i = 0; i < sizes.size(); i++) {
            int size1 = sizes.get(i);
            int size2 = reversedSizes.get(i);
            ret.add(DynamicTest.dynamicTest(
                    String.format("testFullDuplex() - size1=%s, size2=%s", size1, size2), () -> {
                        SocketGroups.SocketPair socketPair = factory.nioNio(
                                Option.apply(null),
                                Option.apply(new ChunkSizeConfig(
                                        new ChuckSizes(Optional.of(size1), Optional.of(size2)),
                                        new ChuckSizes(Optional.of(size1), Optional.of(size2)))),
                                true,
                                false,
                                Option.apply(channelGroup));
                        Loops.fullDuplex(socketPair, dataSize);
                    }));
        }
        return ret;
    }
}
