package tlschannel.example;

import tlschannel.NeedsReadException;
import tlschannel.NeedsWriteException;
import tlschannel.ServerTlsChannel;
import tlschannel.TlsChannel;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Iterator;

/**
 * <p> Server non-blocking example. Accepts any number of connections and echos bytes sent by the clients into standard
 * output.</p>
 *
 * <p> To test, use: </p>
 *
 * <code> openssl s_client -connect localhost:10000 </code>
 *
 * <p> This example is similar to the canonical selector loop, except for: </p>
 *
 * <ul>
 *
 * <li>When a connection arrives, the newly-created channel is wrapped in a TlsChannel: all IO is done using
 * the TlsChannel, but the raw channel is registered in the selector.</li>
 *
 * <li>IO operations are surrounded in a
 * try-catch block that traps {@link NeedsWriteException} and {@link NeedsReadException} and enables the appropriate
 * operation for the corresponding key.</li>
 *
 * </ul>
 */
public class NonBlockingServer {

    private static final Charset utf8 = StandardCharsets.UTF_8;

    public static void main(String[] args) throws IOException, GeneralSecurityException {

        // initialize the SSLContext, a configuration holder, reusable object
        SSLContext sslContext = SimpleBlockingServer.authenticatedContext("TLSv1.2");

        // connect server socket channel and register it in the selector
        try (ServerSocketChannel serverSocket = ServerSocketChannel.open()) {
            serverSocket.socket().bind(new InetSocketAddress(10000));
            serverSocket.configureBlocking(false);
            Selector selector = Selector.open();
            serverSocket.register(selector, SelectionKey.OP_ACCEPT);

            while (true) {

                // loop blocks here
                selector.select();

                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
                while (iterator.hasNext()) {

                    SelectionKey key = iterator.next();
                    iterator.remove();

                    if (key.isAcceptable()) {

                        // we have a new connection

                        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();

                        // accept new connection
                        SocketChannel rawChannel = serverChannel.accept();
                        rawChannel.configureBlocking(false);

                        // wrap raw channel in TlsChannel
                        TlsChannel tlsChannel = ServerTlsChannel.newBuilder(rawChannel, sslContext).build();

                        /*
                         * Wrap raw channel with a TlsChannel. Note that the raw channel is registered in the selector
                         * and the TlsChannel put as an attachment register the channel for reading, because TLS
                         * connections are initiated by clients.
                         */
                        SelectionKey newKey = rawChannel.register(selector, SelectionKey.OP_READ);
                        newKey.attach(tlsChannel);

                    } else if (key.isReadable() || key.isWritable()) {

                        // we have data (or buffer space) in existing connections

                        ByteBuffer buffer = ByteBuffer.allocate(10000);

                        // recover the TlsChannel from the attachment
                        TlsChannel tlsChannel = (TlsChannel) key.attachment();
                        try {
                            // write received bytes in stdout
                            int c = tlsChannel.read(buffer);
                            if (c > 0) {
                                buffer.flip();
                                System.out.print(utf8.decode(buffer));
                            }
                            if (c < 0) {
                                tlsChannel.close();
                            }
                        } catch (NeedsReadException e) {
                            key.interestOps(SelectionKey.OP_READ); // overwrites previous value
                        } catch (NeedsWriteException e) {
                            key.interestOps(SelectionKey.OP_WRITE); // overwrites previous value
                        }

                    } else {
                        throw new IllegalStateException();
                    }
                }
            }
        }
    }
}
