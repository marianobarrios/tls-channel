package tlschannel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.net.ssl.SSLEngine;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import scala.Option;
import scala.Some;
import tlschannel.helpers.Loops;
import tlschannel.helpers.SocketGroups;
import tlschannel.helpers.SocketPairFactory;
import tlschannel.helpers.SocketPairFactory.ChuckSizes;
import tlschannel.helpers.SocketPairFactory.ChunkSizeConfig;
import tlschannel.helpers.SslContextFactory;
import tlschannel.util.StreamUtils;

/**
 * Test using a null engine (pass-through). The purpose of the test is to remove the overhead of the real
 * {@link SSLEngine} to be able to test the overhead of the {@link TlsChannel}.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class NullEngineTest {

    private final SslContextFactory sslContextFactory = new SslContextFactory();
    private final SocketPairFactory factory = new SocketPairFactory(sslContextFactory.defaultContext());
    private final int dataSize = 200 * 1024 * 1024;

    {
        // heat cache
        Loops.expectedBytesHash.apply(dataSize);
    }

    // null engine - half duplex - heap buffers
    @TestFactory
    public Collection<DynamicTest> testHalfDuplexHeapBuffers() {
        System.out.println("testHalfDuplexHeapBuffers():");
        List<Integer> sizes = StreamUtils.iterate(512, x -> x < SslContextFactory.tlsMaxDataSize() * 2, x -> x * 2)
                .collect(Collectors.toList());
        List<DynamicTest> tests = new ArrayList<>();
        for (int size1 : sizes) {
            DynamicTest test = DynamicTest.dynamicTest(String.format("Testing sizes: size1=%s", size1), () -> {
                SocketGroups.SocketPair socketPair = factory.nioNio(
                        null,
                        Some.apply(new ChunkSizeConfig(
                                new ChuckSizes(Optional.of(size1), Optional.empty()),
                                new ChuckSizes(Optional.of(size1), Optional.empty()))),
                        true,
                        false,
                        Option.apply(null));
                Loops.halfDuplex(socketPair, dataSize, false, false);
                System.out.printf("-eng-> %5d -net-> %5d -eng->\n", size1, size1);
            });
            tests.add(test);
        }
        return tests;
    }

    // null engine - half duplex - direct buffers
    @TestFactory
    public Collection<DynamicTest> testHalfDuplexDirectBuffers() {
        System.out.println("testHalfDuplexDirectBuffers():");
        List<Integer> sizes = StreamUtils.iterate(512, x -> x < SslContextFactory.tlsMaxDataSize() * 2, x -> x * 2)
                .collect(Collectors.toList());
        List<DynamicTest> tests = new ArrayList<>();
        for (int size1 : sizes) {
            DynamicTest test = DynamicTest.dynamicTest(String.format("Testing sizes: size1=%s", size1), () -> {
                SocketGroups.SocketPair socketPair = factory.nioNio(
                        null,
                        Some.apply(new ChunkSizeConfig(
                                new ChuckSizes(Optional.of(size1), Optional.empty()),
                                new ChuckSizes(Optional.of(size1), Optional.empty()))),
                        true,
                        false,
                        Option.apply(null));
                Loops.halfDuplex(socketPair, dataSize, false, false);
                System.out.printf("-eng-> %5d -net-> %5d -eng->\n", size1, size1);
            });
        }
        return tests;
    }

    @AfterAll
    public void afterAll() {
        System.out.println(factory.getGlobalAllocationReport());
    }
}
