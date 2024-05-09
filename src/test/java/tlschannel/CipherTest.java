package tlschannel;

import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import tlschannel.helpers.Loops;
import tlschannel.helpers.SocketGroups.SocketPair;
import tlschannel.helpers.SocketPairFactory;
import tlschannel.helpers.SslContextFactory;

@TestInstance(Lifecycle.PER_CLASS)
public class CipherTest {

    private final List<String> protocols;
    private final int dataSize = 200 * 1000;

    public CipherTest() {
        try {
            String[] allProtocols =
                    SSLContext.getDefault().getSupportedSSLParameters().getProtocols();
            protocols = Arrays.stream(allProtocols)
                    .filter(x -> !x.equals("SSLv2Hello"))
                    .collect(Collectors.toList());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException();
        }
    }

    // Test a half-duplex interaction, with renegotiation before reversing the direction of the flow (as in HTTP)
    @TestFactory
    public Collection<DynamicTest> testHalfDuplexWithRenegotiation() {
        System.out.println("testHalfDuplexWithRenegotiation():");
        List<DynamicTest> tests = new ArrayList<>();
        for (String protocol : protocols) {
            SslContextFactory ctxFactory = new SslContextFactory(protocol);
            for (String cipher : ctxFactory.getAllCiphers()) {
                tests.add(DynamicTest.dynamicTest(
                        String.format("testHalfDuplexWithRenegotiation() - protocol: %s, cipher: %s", protocol, cipher),
                        () -> {
                            SocketPairFactory socketFactory = new SocketPairFactory(ctxFactory.defaultContext());
                            SocketPair socketPair = socketFactory.nioNio(
                                    Optional.of(cipher), Optional.empty(), true, false, Optional.empty());
                            Loops.halfDuplex(socketPair, dataSize, protocol.compareTo("TLSv1.2") < 0, false);
                            String actualProtocol = socketPair
                                    .client
                                    .tls
                                    .getSslEngine()
                                    .getSession()
                                    .getProtocol();
                            String p = String.format("%s (%s)", protocol, actualProtocol);
                            System.out.printf("%-18s %-50s\n", p, cipher);
                        }));
            }
        }
        return tests;
    }

    // Test a full-duplex interaction, without any renegotiation
    @TestFactory
    public Collection<DynamicTest> testFullDuplex() {
        List<DynamicTest> tests = new ArrayList<>();
        for (String protocol : protocols) {
            SslContextFactory ctxFactory = new SslContextFactory(protocol);
            for (String cipher : ctxFactory.getAllCiphers()) {
                tests.add(DynamicTest.dynamicTest(
                        String.format("testFullDuplex() - protocol: %s, cipher: %s", protocol, cipher), () -> {
                            SocketPairFactory socketFactory = new SocketPairFactory(ctxFactory.defaultContext());
                            SocketPair socketPair = socketFactory.nioNio(
                                    Optional.of(cipher), Optional.empty(), true, false, Optional.empty());
                            Loops.fullDuplex(socketPair, dataSize);
                            String actualProtocol = socketPair
                                    .client
                                    .tls
                                    .getSslEngine()
                                    .getSession()
                                    .getProtocol();
                            String p = String.format("%s (%s)", protocol, actualProtocol);
                            System.out.printf("%-18s %-50s\n", p, cipher);
                        }));
            }
        }
        return tests;
    }
}
