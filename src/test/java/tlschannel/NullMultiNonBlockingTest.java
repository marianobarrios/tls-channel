package tlschannel;

import static tlschannel.helpers.SocketPairFactory.NULL_CIPHER;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import tlschannel.helpers.NonBlockingLoops;
import tlschannel.helpers.SocketGroups.SocketPair;
import tlschannel.helpers.SocketPairFactory;
import tlschannel.helpers.SslContextFactory;

/** Test using concurrent, non-blocking connections, and a "null" [[javax.net.ssl.SSLEngine]] that just passes all byte
 * as they are.
 */
@TestInstance(Lifecycle.PER_CLASS)
public class NullMultiNonBlockingTest {

    private final SslContextFactory sslContextFactory = new SslContextFactory();
    private final SocketPairFactory factory = new SocketPairFactory(sslContextFactory.defaultContext());
    private final int dataSize = 10 * 1024 * 1024;
    private final int totalConnections = 50;

    @Test
    public void testRunTasksInNonBlockingLoop() {
        List<SocketPair> pairs = factory.nioNioN(
                Optional.of(NULL_CIPHER), totalConnections, Optional.empty(), true, false, Optional.empty());
        NonBlockingLoops.Report report = NonBlockingLoops.loop(pairs, dataSize, false);
        Assertions.assertEquals(0, report.asyncTasksRun);
    }

    @AfterAll
    public void afterAll() {
        System.out.println(factory.getGlobalAllocationReport());
    }
}
