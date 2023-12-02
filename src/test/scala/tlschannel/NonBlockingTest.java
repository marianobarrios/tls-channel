package tlschannel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import scala.Option;
import scala.Some;
import scala.jdk.javaapi.CollectionConverters;
import tlschannel.helpers.NonBlockingLoops;
import tlschannel.helpers.SocketPair;
import tlschannel.helpers.SocketPairFactory;
import tlschannel.helpers.SocketPairFactory.ChuckSizes;
import tlschannel.helpers.SocketPairFactory.ChunkSizeConfig;
import tlschannel.helpers.SslContextFactory;
import tlschannel.util.ListUtils;
import tlschannel.util.StreamUtils;

@TestInstance(Lifecycle.PER_CLASS)
public class NonBlockingTest {

    private final SslContextFactory sslContextFactory = new SslContextFactory();
    private final SocketPairFactory factory = new SocketPairFactory(sslContextFactory.defaultContext());
    private final int dataSize = 200 * 1024;

    @TestFactory
    public Collection<DynamicTest> testSelectorLoop() {
        System.out.println("testSelectorLoop():");
        List<Integer> sizes = StreamUtils.iterate(1, x -> x < SslContextFactory.tlsMaxDataSize() * 2, x -> x * 2)
                .collect(Collectors.toList());
        List<Integer> reversedSizes = ListUtils.reversed(sizes);
        List<DynamicTest> ret = new ArrayList<>();
        for (int i = 0; i < sizes.size(); i++) {
            int size1 = sizes.get(i);
            int size2 = reversedSizes.get(i);
            ret.add(DynamicTest.dynamicTest(
                    String.format("testSelectorLoop() - size1=%d, size2=%d", size1, size2), () -> {
                        SocketPair socketPair = factory.nioNio(
                                Option.apply(null),
                                Some.apply(new ChunkSizeConfig(
                                        new ChuckSizes(Some.apply(size1), Some.apply(size2)),
                                        new ChuckSizes(Some.apply(size1), Some.apply(size2)))),
                                true,
                                false,
                                Option.apply(null));

                        NonBlockingLoops.Report report = NonBlockingLoops.loop(
                                CollectionConverters.asScala(Collections.singletonList(socketPair))
                                        .toSeq(),
                                dataSize,
                                true);
                        System.out.printf("%5d -eng-> %5d -net-> %5d -eng-> %5d\n", size1, size2, size1, size2);
                        report.print();
                    }));
        }
        return ret;
    }
}
