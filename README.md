# TLS Channel

TLS Channel is a library that implements a [ByteChannel](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/ByteChannel.html) interface over a [TLS](https://tools.ietf.org/html/rfc5246) (Transport Layer Security) connection. It delegates all cryptographic operations to the standard Java TLS implementation, [SSLEngine](https://docs.oracle.com/javase/8/docs/api/javax/net/ssl/SSLEngine.html), effectively hiding it behind an easy-to-use streaming API that allows JVM applications to be secured with minimal added complexity.

In other words, it is a simple library that allows the programmer to implement TLS using the same standard socket API used for plaintext, much like OpenSSL does for C, but for Java. It fills a particularly painful missing feature of the standard library.

[![Build Status](https://github.com/marianobarrios/tls-channel/actions/workflows/main.yml/badge.svg)](https://github.com/marianobarrios/tls-channel/actions)

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.marianobarrios/tls-channel/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.marianobarrios/tls-channel)
[![Javadoc](https://javadoc.io/badge2/com.github.marianobarrios/tls-channel/javadoc.svg)](https://javadoc.io/doc/com.github.marianobarrios/tls-channel)

### Main features

- Implements [ByteChannel](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/ByteChannel.html), [GatheringByteChannel](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/GatheringByteChannel.html), and [ScatteringByteChannel](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/ScatteringByteChannel.html), the same interfaces implemented by [SocketChannel](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/SocketChannel.html), effectively making encryption an implementation detail. There is no need to directly call SSLEngine except for the initial setup.
- Works for both client- and server-side TLS.
- Server-side SNI: Supports choosing different [SSLContexts](https://docs.oracle.com/javase/8/docs/api/javax/net/ssl/SSLContext.html), depending on the received [Server Name Indication](https://tools.ietf.org/html/rfc6066#page-6) sent by incoming connections (this feature is not supported at all by SSLEngine, but is universally used by web browsers and servers).
- Supports both **blocking and non-blocking** modes, using the same API, just as SocketChannel does with unencrypted connections.
- Supports full-duplex usage, without any cross-locking between read and write operations.
- Pluggable buffer strategy (this is useful for GC-saving buffer pooling, or to use direct buffers to speed up I/O).
- Full and **automatic zeroing** of all plaintext contained in internal buffers immediately after the data is no longer necessary (a feature present in [boringssl](https://boringssl.googlesource.com/boringssl/), Google's fork of OpenSSL).
- **Opportunistic buffer release** (akin to OpenSSL's SSL_MODE_RELEASE_BUFFERS option), which significantly reduces the memory footprint of idle connections.
- Full control over **TLS shutdown** to prevent truncation attacks.
- An implementation of [AsynchronousByteChannel](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/AsynchronousByteChannel.html) is supplied, offering compatibility for this higher-level API based on callbacks and futures.

### Non-features

Being an API layer, TLS Channel *delegates all cryptographic operations to SSLEngine*, leveraging it 100%. This implies that:

- Except for a few bytes of parsing at the beginning of server-side connections to implement SNI, **the entire protocol implementation is done by the SSLEngine**. Note that **this parsing is not done at all if SNI support is disabled**.
- Both the SSLContext and SSLEngine are supplied by the API user; these classes are responsible for protocol configuration, including hostname validation, client-side authentication, encryption, protocol implementation, etc. This means that **no cryptographic operation whatsoever is done in this library**.
- Application-Layer Protocol Negotiation (ALPN), supported by SSLEngine since Java 9, also works independently of this library, as the negotiation strategy is configured directly using SSLEngine.

## Rationale

TLS is the world's most used encryption protocol. Created by Netscape in 1994 as SSL (Secure Socket Layer), it saw widespread adoption, which eventually led to its standardization. TLS works on top of the Transport Control Protocol (TCP), maintaining its core abstractions: two independent byte streams, one in each direction, with ordered at-most-once delivery. It can be argued that part of the success of TLS was due to its convenient programming interface, which is similar to the highly successful and familiar Berkeley Sockets. Currently, there are a few widely used implementations:

- The most used TLS library is [OpenSSL](https://www.openssl.org/). Written in C (and, along with some forks, is the *de facto* standard for C and C++), it is also widely used in Python, PHP, Ruby, and Node.js.
- The Go language has its own implementation, package [crypto/tls](https://golang.org/pkg/crypto/tls/).
- Another C library by Mozilla is part of the "[Network Security Services](https://developer.mozilla.org/en-US/docs/Mozilla/Projects/NSS)" (NSS) group of libraries. It is the evolution of the original library written by Netscape and is now notably used by the Firefox browser.

And many more. As noted, all these libraries implement a streaming interface, and most also let the user switch freely between blocking and non-blocking behavior. But in Java, the history, unfortunately, is not so simple.

### The Java TLS problem

In Java, support for TLS (then SSL) was added in version 1.2 (as an optional package) in the form of a subclass of the [Socket](https://docs.oracle.com/javase/8/docs/api/java/net/Socket.html) class: [SSLSocket](https://docs.oracle.com/javase/8/docs/api/javax/net/ssl/SSLSocket.html). Being a subclass, once instantiated, the way of using it was exactly the same as the unencrypted original. That worked (and still works) well enough. Nevertheless, the Java I/O API already had some limitations, and an update was needed.

#### java.nio

In version 1.4, a [new I/O API](https://docs.oracle.com/javase/8/docs/api/java/nio/package-summary.html) was launched (java.nio). It superseded the old I/O API, starting an implicit (and very long) deprecation cycle. New features included:

- Non-blocking operations.
- A higher-level API, based on wrapped buffers ([ByteBuffers](https://docs.oracle.com/javase/8/docs/api/java/nio/ByteBuffer.html)).
- Direct I/O, with "direct" ByteBuffers that can live outside the heap. This is especially advantageous for sockets, as the JVM forces an extra copy of any heap-based array sent in a native call (to facilitate synchronization with the garbage collector). Not having the buffer in the heap avoids this step, improving performance (at the cost of more complicated memory management).
- "[Scattering](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/ScatteringByteChannel.html)" and "[gathering](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/GatheringByteChannel.html)" API, which is the ability to use more than one sequential buffer in the same I/O operation.

But there was no TLS support, which was only available in old-style sockets.

#### SSLEngine

Version 1.5 saw the advent of [SSLEngine](https://docs.oracle.com/javase/8/docs/api/javax/net/ssl/SSLEngine.html) as the official way of doing TLS over NIO sockets. This API has been the official option for more than a decade. However, it has severe shortcomings:

- No streaming support. SSLEngine does not do any I/O or keep any buffers. It does all cryptographic operations on user-managed buffers (but, confusingly, it simultaneously keeps internal state associated with the TLS connection). This no-data but stateful API is just not what users expect or are used to, and indeed not what the rest of the industry has standardized on.
- Even considering the constraints, the API is unnecessarily convoluted, with too big a surface and many incorrect interactions that are not prevented at compile-time. It's just extremely hard to use correctly.
- No support for server-side SNI handling.

#### What to do

Of course, many programmers don't manipulate TCP or TLS streams directly but use protocol libraries (e.g., [Apache HttpClient](https://hc.apache.org/httpcomponents-client-ga/), or the newer [java.net.http](https://docs.oracle.com/en/java/javase/11/docs/api/java.net.http/java/net/http/package-summary.html)). However, in cases where direct socket-like access is needed, the programmer has essentially three alternatives:

1. Use the old (implicitly deprecated) socket API. This implies being subject to its limitations, which means, among other things, only blocking behavior.
2. Use SSLEngine directly. As said, this is a difficult task, which is _very_ hard to accomplish correctly, and in most cases completely out of proportion to the effort of writing the application code.
3. Use some higher-level I/O library, like [Netty](https://netty.io/), [Project Grizzly](https://javaee.github.io/grizzly/), [Apache Mina](https://mina.apache.org/), or [JBoss XNIO](http://xnio.jboss.org/). They supply event architectures that intend to ease the task of writing programs that use non-blocking I/O. They are usually big, framework-like libraries, sometimes with their own dependencies. Using one of these is the path chosen by many, but it is not an option if the programmer cannot commit to a particular event architecture, couple the application code to an idiosyncratic library, or include a big dependency.

All three alternatives have been taken by many Java libraries and applications, with no clear preference among leading open-source Java projects. Even though these options can work reasonably well, there was still no clear and standard solution.

#### Non-SSLEngine TLS in Java

There is actually no strict need to use SSLEngine. The two most common alternatives are:

- Using the [Java Native Interface](https://docs.oracle.com/javase/8/docs/technotes/guides/jni/) (JNI) and calling OpenSSL (or another C library). The Tomcat project has a widely used "[native](http://tomcat.apache.org/native-doc/)" library that eases that task. While using native code can work, it has obvious shortcomings, especially regarding packaging, distribution, type compatibility, and runtime safety.
- "[The Legion of the Bouncy Castle](https://www.bouncycastle.org/)" has a "lightweight" TLS API that supports streaming. This actually works, but only in blocking mode, effectively just like using the old SSLSocket API.

Of course, these options imply using an alternative cryptographic implementation, which may not be desired.

### Existing open-source SSLEngine users

The feat of using SSLEngine directly is indeed performed by several projects, both general-purpose I/O libraries and implementations of particular protocols. Below is an inevitably incomplete list of open-source examples (some of them unmaintained). Every one in the list contains essentially the same general-purpose, SSLEngine-calling code, only embedded in custom types and semantics. That said, these examples, while not really suited for reuse, have been invaluable for both appreciating the difficulty of the task and also as a source of ideas.

| Type | Project | Package/class |
| --- | --- | --- |
| I/O framework | [Eclipse Grizzly](https://projects.eclipse.org/projects/ee4j.grizzly) | [org.glassfish.grizzly.ssl](https://github.com/eclipse-ee4j/glassfish-grizzly/tree/main/modules/grizzly/src/main/java/org/glassfish/grizzly/ssl) |
| I/O framework | [Netty Project](https://netty.io/) | [io.netty.handler.ssl.SslHandler](https://github.com/netty/netty/blob/4.2/handler/src/main/java/io/netty/handler/ssl/SslHandler.java) |
| I/O framework | [Apache Mina](https://mina.apache.org/) | [org.apache.mina.transport.nio](https://github.com/apache/mina/blob/trunk/core/src/main/java/org/apache/mina/transport/nio/SslHelper.java) |
| I/O framework | [JBoss XNIO](https://xnio.jboss.org/) | [org.xnio.ssl.JsseStreamConduit](https://github.com/xnio/xnio/blob/3.x/api/src/main/java/org/xnio/ssl/JsseStreamConduit.java) |
| HTTP server | [Apache Tomcat](https://tomcat.apache.org/) | [org.apache.tomcat.util.net.SecureNio2Channel](https://github.com/apache/tomcat/blob/main/java/org/apache/tomcat/util/net/SecureNioChannel.java) |
| HTTP server | [OpenJDK](http://openjdk.java.net/) | [sun.net.httpserver.SSLStreams](https://github.com/openjdk/jdk/blob/master/src/jdk.httpserver/share/classes/sun/net/httpserver/SSLStreams.java) |
| HTTP client/server | [Apache HttpComponents](https://hc.apache.org/) | [org.apache.hc.core5.reactor.ssl.SSLIOSession](https://github.com/apache/httpcomponents-core/blob/master/httpcore5/src/main/java/org/apache/hc/core5/reactor/ssl/SSLIOSession.java) |
| HTTP server | [Eclipse Jetty](https://jetty.org/) | [org.eclipse.jetty.io.ssl.SslConnection](https://github.com/jetty/jetty.project/blob/jetty-12.1.x/jetty-core/jetty-io/src/main/java/org/eclipse/jetty/io/ssl/SslConnection.java) |
| Distributed file system | [XtreemFS](https://www.xtreemfs.org/) | [org.xtreemfs.foundation.pbrpc.channels.SSLChannelIO](https://github.com/xtreemfs/xtreemfs/blob/master/java/xtreemfs-foundation/src/main/java/org/xtreemfs/foundation/pbrpc/channels/SSLChannelIO.java) |
| Tor client | [Orchid](https://subgraph.com/orchid/index.en.html) | [com.subgraph.orchid.sockets.sslengine.SSLEngineManager](https://github.com/subgraph/Orchid/blob/master/src/com/subgraph/orchid/sockets/sslengine/SSLEngineManager.java) |

## Usage

Being an instance of ByteChannel, normal I/O operations are just done in the usual way. For instantiation of both client and server connections, builders are used:

```java
ByteChannel rawChannel = ...
SSLContext sslContext = ...
TlsChannel tlsChannel = ClientTlsChannel
    .newBuilder(rawChannel, sslContext)
    .build();
```

```java
ByteChannel rawChannel = ...
SSLContext sslContext = ...
TlsChannel tlsChannel = ServerTlsChannel
    .newBuilder(rawChannel, sslContext)
    .build();
```

Typical usage involves creating either a [ClientTlsChannel](https://javadoc.io/doc/com.github.marianobarrios/tls-channel/latest/tlschannel/ClientTlsChannel.html) or a [ServerTlsChannel](https://javadoc.io/doc/com.github.marianobarrios/tls-channel/latest/tlschannel/ServerTlsChannel.html), for client and server connections respectively. Both classes implement [TlsChannel](https://javadoc.io/doc/com.github.marianobarrios/tls-channel/latest/tlschannel/TlsChannel.html), where most of the methods are defined.

Complete examples:

- [Simple blocking client](src/test/java/tlschannel/example/SimpleBlockingClient.java)
- [Simple blocking server](src/test/java/tlschannel/example/SimpleBlockingServer.java)

### Non-blocking

Standard ByteChannel instances communicate the fact that operations would block—and so that they should be retried when the channel is ready—by returning zero. However, as TLS handshakes happen transparently and involve multiple messages from each side, both a read and a write operation could be blocked waiting for either a read (byte available) or a write (buffer space available) in the underlying socket. That is, some way to distinguish between read- or write-related blocking is needed.

Ideally, a more complex return type would suffice—not merely an `int` but some object including more information. For instance, OpenSSL uses special error codes for these conditions: `SSL_ERROR_WANT_READ` and `SSL_ERROR_WANT_WRITE`.

In the case of TLS Channel, it is in practice necessary to maintain compatibility with the existing ByteChannel interface. That's why a somewhat unorthodox approach is used: when the operation would block, special exceptions are thrown: [NeedsReadException](https://javadoc.io/doc/com.github.marianobarrios/tls-channel/latest/tlschannel/NeedsReadException.html) and [NeedsWriteException](https://javadoc.io/doc/com.github.marianobarrios/tls-channel/latest/tlschannel/NeedsWriteException.html), meaning that the operation should be retried when the underlying channel is ready for reading or writing, respectively.

Typical usage inside a selector loop looks like this:

```java
try {
    int c = tlsChannel.read(buffer);
    ...
} catch (NeedsReadException e) {
    key.interestOps(SelectionKey.OP_READ);
} catch (NeedsWriteException e) {
    key.interestOps(SelectionKey.OP_WRITE);
}
```

Complete examples:

- [Non-blocking client](src/test/java/tlschannel/example/NonBlockingClient.java)
- [Non-blocking server](src/test/java/tlschannel/example/NonBlockingServer.java)

### Off-loop tasks

Selector loops work under the assumption that they don't (mostly) block. This is enough when it is possible to have as many loops as CPU cores. However, Java selectors don't work very well with multiple threads, requiring complicated synchronization; this leads to them being used almost universally from a single thread.

A single I/O thread is generally enough for plaintext connections. But TLS can be CPU-intensive, in particular asymmetric cryptography when establishing sessions. Fortunately, SSLEngine encapsulates those, returning [Runnable](https://docs.oracle.com/javase/8/docs/api/java/lang/Runnable.html) objects that the client code can run in any thread. TLS Channel can be configured to either run those as part of I/O operations (that is, in-thread)—the default behavior—or not, letting the calling code handle them. The latter option should be enabled at construction time:

```java
TlsChannel tlsChannel = ServerTlsChannel
    .newBuilder(rawChannel, sslContext)
    .withRunTasks(false)
    .build();
```

An exception ([NeedsTaskException](https://javadoc.io/doc/com.github.marianobarrios/tls-channel/latest/tlschannel/NeedsTaskException.html)) is then used to communicate that a task is ready to run. (Using an exception is needed for the same reasons explained in the previous section):

```java
try {
    int c = tlsChannel.read(buffer);
    ...
} catch ...
} catch (NeedsTaskException e) {
    taskExecutor.execute(() -> {
        e.getTask().run();
        // when the task finished, add it to the ready-set
        // taskReadyKeys should be a concurrent set that should be checked
        // and emptied as part of the selector loop
        taskReadyKeys.add(key);
        selector.wakeup(); // unblock the selector
    });
}
```

Complete example: [non-blocking server with off-loop tasks](src/test/java/tlschannel/example/NonBlockingServerWithOffLoopTasks.java)

### Server Name Indication – server side

The [Server Name Indication](https://tools.ietf.org/html/rfc6066#page-6) (SNI) is a special TLS extension designed to solve a chicken-and-egg situation between the certificate offered by the server (which depends on the host required by the client for multi-host servers) and the host name sent by the client in HTTP request headers (necessarily after the connection is established). The SNI extension allows the client to anticipate the required host name in the ClientHello message.

Java added support for SNI in version 7. The feature can be accessed using the [SSLParameters](https://docs.oracle.com/javase/8/docs/api/javax/net/ssl/SSLParameters.html) class. Sadly, this only works on the client side. For the server, the class only allows accepting or rejecting connections based on the host name, not choosing the certificate offered.

In TLS Channel, to use SNI-based selection of the SSLContext, a different builder factory method exists, which receives instances of [SniSslContextFactory](https://javadoc.io/doc/com.github.marianobarrios/tls-channel/latest/tlschannel/SniSslContextFactory.html).

```java
SniSslContextFactory contextFactory = (Optional<SNIServerName> sniServerName) -> {
    Optional<SSLContext> ret = ...
    return ret;
};
TlsChannel tlsChannel = ServerTlsChannel
    .newBuilder(rawChannel, contextFactory)
    .build();
```

Complete example: [SNI-aware server](src/test/java/tlschannel/example/SniBlockingServer.java)

### AsynchronousByteChannel

Java 1.7 introduced "asynchronous" byte channels. This infrastructure offers a higher-level API for non-blocking I/O, using callbacks and futures. Again, TLS is not supported by the standard API. TLS Channel offers complete support for this programming style using the [async](https://www.google.com/search?q=src/main/java/tlschannel/async) package.

```java
// build a singleton-like channel group
AsynchronousTlsChannelGroup channelGroup = new AsynchronousTlsChannelGroup();

// build asynchronous channel, based in an existing TLS channel and the group
AsynchronousTlsChannel asyncTlsChannel = new AsynchronousTlsChannel(channelGroup, tlsChannel, rawChannel);

// use as any other AsynchronousByteChannel
asyncTlsChannel.read(res, null, new CompletionHandler<Integer, Object>() {
    ...
};
```

Complete example: [Asynchronous channel server](src/test/java/tlschannel/example/AsynchronousChannelServer.java)

## Buffers

TLS Channel uses buffers for its operation. Every channel uses at least two ciphertext buffers that hold ciphertext, one for reading from the underlying channel and the other for writing to it. Additionally, a third plaintext buffer may be needed for read operations when the user-supplied buffer is smaller than the minimum SSLEngine needs for placing the decrypted bytes.

All buffers are created from optionally user-supplied factories (instances of [BufferAllocator](https://javadoc.io/doc/com.github.marianobarrios/tls-channel/latest/tlschannel/BufferAllocator.html)). It is also possible to supply different allocators for plain and ciphertext; for example:

```java
TlsChannel tlsChannel = ServerTlsChannel
    .newBuilder(rawChannel, sslContext)
    .withPlainBufferAllocator(new HeapBufferAllocator())
    .withEncryptedBufferAllocator(new DirectBufferAllocator())
    .build();
```

The rationale for using direct ciphertext buffers is that, in the most common use case, the underlying channel is a [SocketChannel](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/SocketChannel.html). This channel actually does native I/O operations, which are generally faster using direct buffers.

By default, heap buffers are used to maximize compatibility with different virtual machines, as direct ones are implementation-dependent.

### Zeroing

Buffers containing plain text are always immediately zeroed after the bytes are returned. This feature is intended as a mitigation against other security vulnerabilities that may appear (like, for example, [CVE-2014-0160](https://nvd.nist.gov/vuln/detail/CVE-2014-0160)). This is also present in [boringssl](https://boringssl.googlesource.com/boringssl/), Google's fork of OpenSSL.

Due to the minuscule performance penalty and significant security benefits, zeroing cannot be disabled.

### Buffer release

TLS Channel supports opportunistic buffer release, a feature similar to OpenSSL's `SSL_MODE_RELEASE_BUFFERS` option. If, after any operation, a buffer does not contain any pending bytes, it is released back to the pool. This feature can dramatically reduce memory consumption in the case of long-lived idle connections, which tends to happen when implementing server-side HTTPS.

This option is enabled by default and could be disabled if desired:

```java
TlsChannel tlsChannel = ServerTlsChannel
    .newBuilder(rawChannel, sslContext)
    .withReleaseBuffers(false)
    .build();
```

## Compatibility and certificate validation

Because the protocol implementation is fully delegated to SSLEngine, there are no limitations regarding TLS versions: whatever is supported by the Java implementation used will work.

The same applies to certificate validation. All configuration is done using the SSLContext object, which TLS Channel takes as is.

## Implementation

### Requirements

TLS Channel requires Java 8 or newer.

### Size and Dependencies

The library has no dependencies. The main jar file size is below 65 KiB.

### Logging

The library uses [Java Logging](https://docs.oracle.com/en/java/javase/20/core/java-logging-overview.html#GUID-B83B652C-17EA-48D9-93D2-563AE1FF8EDA). As a policy, _all_ logging events emitted by the core package are at `FINEST` level, which is below the default threshold in most logging implementations and thus completely silent by default. The optional `tlschannel.async` package can log with higher severity in exceptional circumstances, as it manages threads internally.

## Similar efforts

- [NIO SSL](https://github.com/baswerc/niossl) is a simple library with a similar purpose, written by Corey Baswell.
- [sslfacade](https://github.com/kashifrazzaqui/sslfacade) is an attempt to offer a more reasonable interface than SSLEngine, written by Kashif Razzaqui.
- [sslengine.example](https://github.com/alkarn/sslengine.example) shows how to use SSLEngine, wrapping it in custom classes. It was written by Alex Karnezis.

## Credits

This work is based on a preliminary implementation by [Claudio Martinez](https://github.com/cldmartinez/) and [Mariano Barrios](https://github.com/marianobarrios/) at [Despegar](https://github.com/despegar).