package tlschannel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import scala.Option;
import tlschannel.helpers.Loops;
import tlschannel.helpers.SocketGroups.SocketPair;
import tlschannel.helpers.SocketPairFactory;
import tlschannel.helpers.SslContextFactory;

@TestInstance(Lifecycle.PER_CLASS)
public class ScatteringTest {

    private final SslContextFactory sslContextFactory = new SslContextFactory();
    private final SocketPairFactory factory = new SocketPairFactory(sslContextFactory.defaultContext());

    private static final int dataSize = 150 * 1000;

    @Test
    public void testHalfDuplex() {
        SocketPair socketPair = factory.nioNio(Option.apply(null), Option.apply(null), true, false, Option.apply(null));
        Loops.halfDuplex(socketPair, dataSize, true, false);
    }
}
