package tlschannel.helpers;

import java.nio.channels.ByteChannel;
import java.nio.channels.SocketChannel;
import javax.net.ssl.SSLSocket;
import tlschannel.TlsChannel;
import tlschannel.async.ExtendedAsynchronousByteChannel;

public class SocketGroups {

    public static class OldOldSocketPair {
        public final SSLSocket client;
        public final SSLSocket server;

        public OldOldSocketPair(SSLSocket client, SSLSocket server) {
            this.client = client;
            this.server = server;
        }
    }

    public static class OldNioSocketPair {
        public final SSLSocket client;
        public final SocketGroup server;

        public OldNioSocketPair(SSLSocket client, SocketGroup server) {
            this.client = client;
            this.server = server;
        }
    }

    public static class NioOldSocketPair {
        public final SocketGroup client;
        public final SSLSocket server;

        public NioOldSocketPair(SocketGroup client, SSLSocket server) {
            this.client = client;
            this.server = server;
        }
    }

    public static class SocketPair {
        public final SocketGroup client;
        public final SocketGroup server;

        public SocketPair(SocketGroup client, SocketGroup server) {
            this.client = client;
            this.server = server;
        }
    }

    public static class AsyncSocketPair {
        public final AsyncSocketGroup client;
        public final AsyncSocketGroup server;

        public AsyncSocketPair(AsyncSocketGroup client, AsyncSocketGroup server) {
            this.client = client;
            this.server = server;
        }
    }

    public static class SocketGroup {
        public final ByteChannel external;
        public final TlsChannel tls;
        public final SocketChannel plain;

        public SocketGroup(ByteChannel external, TlsChannel tls, SocketChannel plain) {
            this.external = external;
            this.tls = tls;
            this.plain = plain;
        }
    }

    public static class AsyncSocketGroup {
        public final ExtendedAsynchronousByteChannel external;
        public final TlsChannel tls;
        public final SocketChannel plain;

        public AsyncSocketGroup(ExtendedAsynchronousByteChannel external, TlsChannel tls, SocketChannel plain) {
            this.external = external;
            this.tls = tls;
            this.plain = plain;
        }
    }
}
