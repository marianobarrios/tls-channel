package tlschannel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import scala.Option;
import scala.jdk.CollectionConverters;
import tlschannel.helpers.NonBlockingLoops;
import tlschannel.helpers.SocketGroups.SocketPair;
import tlschannel.helpers.SocketPairFactory;
import tlschannel.helpers.SslContextFactory;

@TestInstance(Lifecycle.PER_CLASS)
public class MultiNonBlockingTest {

    private final SslContextFactory sslContextFactory = new SslContextFactory();
    private final SocketPairFactory factory = new SocketPairFactory(sslContextFactory.defaultContext());
    private final int dataSize = 50 * 1024;
    private final int totalConnections = 200;

    // running tasks in non-blocking loop - no renegotiation
    @Test
    public void testTaskLoop() {
        System.out.println("testTasksInExecutorWithRenegotiation():");
        List<SocketPair> pairs = CollectionConverters.SeqHasAsJava(factory.nioNioN(
                        Option.apply(null), totalConnections, Option.apply(null), true, false, Option.apply(null)))
                .asJava();
        NonBlockingLoops.Report report = NonBlockingLoops.loop(pairs, dataSize, false);
        assertEquals(0, report.asyncTasksRun);
        report.print();
    }

    // running tasks in executor - no renegotiation
    @Test
    public void testTasksInExecutor() {
        System.out.println("testTasksInExecutorWithRenegotiation():");
        List<SocketPair> pairs = CollectionConverters.SeqHasAsJava(factory.nioNioN(
                        Option.apply(null), totalConnections, Option.apply(null), false, false, Option.apply(null)))
                .asJava();
        NonBlockingLoops.Report report = NonBlockingLoops.loop(pairs, dataSize, false);
        report.print();
    }

    // running tasks in non-blocking loop - with renegotiation
    @Test
    public void testTasksInLoopWithRenegotiation() {
        System.out.println("testTasksInExecutorWithRenegotiation():");
        List<SocketPair> pairs = CollectionConverters.SeqHasAsJava(factory.nioNioN(
                        Option.apply(null), totalConnections, Option.apply(null), true, false, Option.apply(null)))
                .asJava();
        NonBlockingLoops.Report report = NonBlockingLoops.loop(pairs, dataSize, true);
        assertEquals(0, report.asyncTasksRun);
        report.print();
    }

    // running tasks in executor - with renegotiation
    @Test
    public void testTasksInExecutorWithRenegotiation() {
        System.out.println("testTasksInExecutorWithRenegotiation():");
        List<SocketPair> pairs = CollectionConverters.SeqHasAsJava(factory.nioNioN(
                        Option.apply(null), totalConnections, Option.apply(null), false, false, Option.apply(null)))
                .asJava();
        NonBlockingLoops.Report report = NonBlockingLoops.loop(pairs, dataSize, true);
        report.print();
    }

    @AfterAll
    public void afterAll() {
        System.out.println(factory.getGlobalAllocationReport());
    }
}
