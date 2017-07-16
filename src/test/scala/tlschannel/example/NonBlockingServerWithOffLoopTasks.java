package tlschannel.example;

import tlschannel.NeedsReadException;
import tlschannel.NeedsTaskException;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * <p> Server non-blocking example, with off-loop tasks processing. Accepts any number of connections and echos bytes
 * sent by the clients into standard output. This example is similar to {@link NonBlockingServer}, except for the fact
 * that "slow" tasks are processed out of the single-thread IO loop. </p>
 *
 * <p> To test, use: </p>
 *
 * <code> openssl s_client -connect localhost:10000 </code>
 *
 * <p> This example is similar to {@link NonBlockingServer} example, except for the fact that the IO operation try-catch
 * block also traps {@link NeedsTaskException}. When those occur, the task is submitted to a helper executor. Some code
 * is added right after the tasks finishes, to add the key to a concurrent set so the selector loop can process it.
 * </p>
 */
public class NonBlockingServerWithOffLoopTasks {

    private static final Charset utf8 = StandardCharsets.UTF_8;

    private static Executor taskExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    private static Set<SelectionKey> taskReadyKeys = ConcurrentHashMap.newKeySet();

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

                // process keys whose task finished
                Iterator<SelectionKey> taskReadyIterator = taskReadyKeys.iterator();
                while (taskReadyIterator.hasNext()) {
                    SelectionKey key = taskReadyIterator.next();
                    taskReadyIterator.remove();
                    handleReadyChannel(selector, key);
                }

                // process keys that had IO events
                Iterator<SelectionKey> ioReadyIterator = selector.selectedKeys().iterator();
                while (ioReadyIterator.hasNext()) {
                    SelectionKey key = ioReadyIterator.next();
                    ioReadyIterator.remove();
                    if (key.isAcceptable()) {
                        // we have a new connection
                        handleNewConnection(sslContext, selector, (ServerSocketChannel) key.channel());
                    } else if (key.isReadable() || key.isWritable()) {
                        // we have data (or buffer space) in existing connections
                        handleReadyChannel(selector, key);
                    } else {
                        throw new IllegalStateException();
                    }
                }
            }
        }
    }

    private static void handleNewConnection(SSLContext sslContext, Selector selector, ServerSocketChannel serverChannel)
            throws IOException {
        // accept new connection
        SocketChannel rawChannel = serverChannel.accept();
        rawChannel.configureBlocking(false);

        // wrap raw channel in TlsChannel
        TlsChannel tlsChannel = ServerTlsChannel
                .newBuilder(rawChannel, sslContext)
                .withRunTasks(false)
                .build();

        /*
         * Wrap raw channel with a TlsChannel. Note that the raw channel is registered in the selector
         * and the TlsChannel put as an attachment register the channel for reading, because TLS
         * connections are initiated by clients.
         */
        SelectionKey newKey = rawChannel.register(selector, SelectionKey.OP_READ);
        newKey.attach(tlsChannel);
    }

    private static void handleReadyChannel(Selector selector, SelectionKey key) throws IOException {

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
        } catch (NeedsTaskException e) {
            taskExecutor.execute(() -> {
                e.getTask().run();
                // when the task finished, add it the the ready-set
                taskReadyKeys.add(key);
                // unblock the selector
                selector.wakeup();
            });
        }
    }
}
