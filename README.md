# TLS Channel

TLS Channel is a library that implements a [ByteChannel](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/ByteChannel.html) interface over a [TLS](https://tools.ietf.org/html/rfc5246) (Transport Layer Security) connection. The library delegates all cryptographic operations to the standard Java TLS implementation: [SSLEngine](https://docs.oracle.com/javase/8/docs/api/javax/net/ssl/SSLEngine.html); effectively hiding it behind an easy-to-use streaming API, that allows to securitize JVM applications with minimal added complexity.

In other words, a simple library that allows the programmer to have TLS using the same standard socket API used for plaintext, just like OpenSSL does for C, only for Java, filling a specially painful missing feature of the standard Java library.

[![Build Status](https://travis-ci.org/marianobarrios/tls-channel.svg?branch=master)](https://travis-ci.org/marianobarrios/tls-channel)

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.github.marianobarrios/tls-channel/badge.svg)](https://maven-badges.herokuapp.com/maven-central/com.github.marianobarrios/tls-channel)
[![Javadoc](http://javadoc-badge.appspot.com/com.github.marianobarrios/tls-channel.svg?label=javadoc)](http://javadoc-badge.appspot.com/com.github.marianobarrios/tls-channel)

### Main features

- Implements [ByteChannel](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/ByteChannel.html), [GatheringByteChannel](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/GatheringByteChannel.html) and [ScatteringByteChannel](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/ScatteringByteChannel.html), the same interfaces implemented by [SocketChannel](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/SocketChannel.html), effectively making encryption an implementation detail. There is no need to directly call SSLEngine except for the initial setup.
- Works for both client and server-side TLS.
- **Server-side SNI**: Supports choosing different [SSLContexts](https://docs.oracle.com/javase/8/docs/api/javax/net/ssl/SSLContext.html) according to the received [Server Name Indication](https://tools.ietf.org/html/rfc6066#page-6) sent by incoming connections (not supported at all by SSLEngine but universally used by web browsers and servers).
- Supports both **blocking and non-blocking** modes, using the same API, just like SocketChannel does with unencrypted connections.
- Pluggable buffer strategy (to do buffer pooling, or to use direct buffers for IO).
- Full and **automatic zeroing** of all the plaintext contained in internal buffers right after the data stops being necessary.
- **Opportunistic buffer release** (akin to OpenSSL's SSL_MODE_RELEASE_BUFFERS option), which significantly reduces the memory footprint of idle cached connections.
- Full control over **TLS shutdown** to prevent truncation attacks.

### Non-features

Being a API layer, TLS Channel delegates all cryptographic operations to SSLEngine, leveraging it 100%. This implies that:

- With the exception of a few bytes of parsing at the beginning of the connection, to look for the SNI, the whole protocol implementation is done by the SSLEngine (this parsing is not done at all if SNI support is not required).
- Both the SSLContext and SSLEngine are supplied by the client; these classes are the ones responsible for protocol configuration, including hostname validation, client-side authentication, encryption, protocol implementation, etc.

## Rationale

By far, the most used cryptography solution is TLS (a.k.a. SSL). TLS works on top of the Transport Control Protocol (TCP), maintaining its core abstractions: two independent byte streams, one in each direction, with ordered at-most-once delivery.

[Recent](https://www.schneier.com/blog/archives/2014/06/gchq_intercept_.html) [trends](https://www.schneier.com/blog/archives/2013/10/nsa_eavesdroppi_2.html) have increased the pressure to add encryption in even more use cases. There have been indeed many efforts to reduce friction regarding security. One example of this is the "[Let's Encrypt](https://www.schneier.com/blog/archives/2013/10/nsa_eavesdroppi_2.html)" project. From the programming perspective, the overwhelming consensus has been to replicate the existing interface (that is, something similar to the highly successful and familiar Berkeley Sockets) for the cryptographic streams:

- The most used TLS library is [OpenSSL](https://www.openssl.org/). Written in C and (along with some forks) the *de facto* standard for C and C++. Also widely used in Python, PHP, Ruby and Node.js.
- The Go language has its own implementation, package [crypto/tls](https://golang.org/pkg/crypto/tls/).
- There is another C library by Mozilla, part of the "[Network Security Services](https://developer.mozilla.org/en-US/docs/Mozilla/Projects/NSS)" (NSS) group of libraries. It's notoriously used by the Firefox browser.

And many more. All this libraries implement a streaming interface, and most let the user switch freely between blocking and non-blocking behavior. But in Java the history, unfortunately, is not so simple.

### The Java TLS problem

In Java, support for TLS (then SSL) was added in version 1.2 (as an optional package) in the form of a subclass of the [Socket](https://docs.oracle.com/javase/8/docs/api/java/net/Socket.html) class: [SSLSocket](https://docs.oracle.com/javase/8/docs/api/javax/net/ssl/SSLSocket.html). Being a subclass, once instantiated, the mode of use was exactly the same as the unencrypted original. That worked (and still works) well enough. Nevertheless, the java IO API already had some limitations, and an update was due.

#### java.nio

In version 1.4, a [new IO API](https://docs.oracle.com/javase/8/docs/api/java/nio/package-summary.html) was launched (java.nio). It superseded the old IO API, starting an implicit (and very long) deprecation cycle. New features include:

- Non-blocking operations.
- A higher lever API, based on wrapped buffers ([ByteBuffers](https://docs.oracle.com/javase/8/docs/api/java/nio/ByteBuffer.html)).
- Direct IO, with "direct" ByteBuffers, that can live out of the heap. This is specially advantageous for sockets, as the JVM forces an extra copy of any heap-based array sent in a native call (to facilitate synchronization with the garbage collector). Not having the buffer in the heap avoids this copy, improving performance (at the cost of slightly more complicated memory management).
- "[Scattering](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/ScatteringByteChannel.html)" and "[gathering](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/GatheringByteChannel.html)" API, that is, the ability to use more than one sequential buffer in the same IO operation.
 
But no TLS support, which was only available in old-style sockets.

#### SSLEngine

Version 1.5 saw the advent of [SSLEngine](https://docs.oracle.com/javase/8/docs/api/javax/net/ssl/SSLEngine.html) as the official way of doing TLS over NIO sockets. This API as been the official option for more than a decade. However, it has severe shortcomings:

- No streaming support. SSLEngine does not do any IO, or keep any buffers. It does all cryptographic operations on user-managed buffers (but, confusingly, at the same time keeps internal state associated with the TLS connection). This no-data—but stateful—API is just not what users expect or are used to, and indeed not what the rest of the industry has standarized on.
- Even considering the constrains, the API is unnecessarily convoluted, with too big a surface, and many incorrect interactions not constrained by the types.
- No support for server-side SNI handling.

#### What to do

Of course, many programmers don't manipulate TCP or TLS streams directly, but use protocol libraries (e.g., [Apache HttpClient](https://hc.apache.org/httpcomponents-client-ga/)). However, in the case that direct socket-like access is needed, the programmer has essentially three alternatives:

1. Use the old (implicitly deprecated) socket API. This implies being subject to its limitations, which means, among other things, only blocking behavior.
2. Use SSLEngine directly. This is a hard task, which is _very_ difficult to accomplish correctly, and in most cases completely out of proportion to the effort of writing the application code.
3. Use some higher-level IO library, like [Netty](https://netty.io/), [Project Grizzly](https://grizzly.java.net/), [Apache Mina](https://mina.apache.org/) or [JBoss XNIO](http://xnio.jboss.org/). These frameworks supply event architectures that intend to easy the task of writing programs that use non-blocking IO. They are big framework-like libraries, sometimes themselves with dependencies. Using one of these is the path chosen by many, but it is not an option if the programmer cannot commit to a particular event architecture, couple the application code to an idiosyncratic library, or include a big dependency.

All three alternatives have been taken by many Java libraries and applications, with no clear preference among leading open-source Java projects. Even though these options can work reasonable well, there was still no clear and standard solution.

#### Non-SSLEngine TLS in Java

There is of course no strict need to use SSLEngine. The two most common alternatives are:

- Use the [Java Native Interface](https://docs.oracle.com/javase/8/docs/technotes/guides/jni/) (JNI) and call OpenSSL. The Tomcat project has a widely used "[native](http://tomcat.apache.org/native-doc/)" library that easies that task. While using native code can work, this as obvious shortcomings, specially regarding distribution, type compatibility and safety.
- "[The Legion of the Bouncy Castle](https://www.bouncycastle.org/)" has a "lightweight" TLS API that supports streaming. This works but only in blocking mode, effectively just like using the old SSLSocket API.

Of course, these options imply using an alternative cryptographic implementation, which may not be desired.

### Existing open-source SSLEngine users

The feat of using SSLEngine directly is indeed performed by several projects, both general purpose IO libraries and implementation of particular protocols. Below is an inevitably incomplete list of open-source examples. Every one in the list contains essentially the same general-purpose, SSLEngine-calling code, only embedded in custom types and semantics. That said, these examples, while not really suited for reuse, have been invaluable for both appreciating the difficulty of the task, and also as a source of ideas.

Type | Project | Package/class
--- | --- | ---
IO framework | [Grizzly](https://grizzly.java.net/) | [org.glassfish.grizzly.ssl](https://java.net/projects/grizzly/sources/git/show/modules/grizzly/src/main/java/org/glassfish/grizzly/ssl)
IO framework | [Netty](https://netty.io/) | [io.netty.handler.ssl.SslHandler](https://github.com/netty/netty/blob/netty-4.1.8.Final/handler/src/main/java/io/netty/handler/ssl/SslHandler.java)
IO framework | [Apache Mina](https://mina.apache.org/) | [org.apache.mina.filter.ssl.SslHandler](https://git-wip-us.apache.org/repos/asf?p=mina.git;a=blob;f=mina-core/src/main/java/org/apache/mina/filter/ssl/SslHandler.java;h=8cd1c802090c3e5c05a4f010e6502aabf23db7de;hb=c1064a07693af79aa4c5069c0046cc462a8d0f68)
IO framework | [XNIO](http://xnio.jboss.org/) | [org.xnio.ssl.JsseStreamConduit](https://github.com/xnio/xnio/blob/3.x/api/src/main/java/org/xnio/ssl/JsseStreamConduit.java)
HTTP server | [Tomcat](http://tomcat.apache.org/) | [org.apache.tomcat.util.net.SecureNio2Channel](http://svn.apache.org/viewvc/tomcat/trunk/java/org/apache/tomcat/util/net/SecureNio2Channel.java?view=markup)
HTTP server | [OpenJDK](http://openjdk.java.net/) | [sun.net.httpserver.SSLStreams](http://cr.openjdk.java.net/~ohair/openjdk7/jdk7-build-copyright/webrev/jdk/src/share/classes/sun/net/httpserver/SSLStreams.java.html)
HTTP client/server | [Apache HttpComponents](https://hc.apache.org/) | [org.apache.http.impl.nio.reactor.SSLIOSession](https://apache.googlesource.com/httpcore/+/trunk/httpcore5/src/main/java/org/apache/hc/core5/reactor/ssl/SSLIOSession.java)
HTTP server | [Jetty](Jetty) | [org.eclipse.jetty.io.ssl.SslConnection](https://github.com/eclipse/jetty.project/blob/master/jetty-io/src/main/java/org/eclipse/jetty/io/ssl/SslConnection.java)
Distributed file system | [XtreemFS](http://www.xtreemfs.org/) | [org.xtreemfs.foundation.pbrpc.channels.SSLChannelIO](https://github.com/xtreemfs/xtreemfs/blob/master/java/xtreemfs-foundation/src/main/java/org/xtreemfs/foundation/pbrpc/channels/SSLChannelIO.java)
Tor client | [Orchid](https://subgraph.com/orchid/index.en.html) | [com.subgraph.orchid.sockets.sslengine.SSLEngineManager](https://github.com/subgraph/Orchid/blob/master/src/com/subgraph/orchid/sockets/sslengine/SSLEngineManager.java)

## Usage

Being an instance of ByteChannel, normal IO operations are just done in the usual way. For instantiation of both client and server connections, builders are used:

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

Typical usage involved creating either a [ClientTlsChannel](https://oss.sonatype.org/service/local/repositories/releases/archive/com/github/marianobarrios/tls-channel/0.1.0/tls-channel-0.1.0-javadoc.jar/!/index.html?tlschannel/ClientTlsChannel.html) or a [ServerTlsChannel](https://oss.sonatype.org/service/local/repositories/releases/archive/com/github/marianobarrios/tls-channel/0.1.0/tls-channel-0.1.0-javadoc.jar/!/index.html?tlschannel/ServerTlsChannel.html), for client and server connections respectively. Both classes implement [TlsChannel](https://oss.sonatype.org/service/local/repositories/releases/archive/com/github/marianobarrios/tls-channel/0.1.0/tls-channel-0.1.0-javadoc.jar/!/index.html?tlschannel/TlsChannel.html), where most of the methods are defined.

Complete examples:

- [Simple blocking client](src/test/scala/tlschannel/example/SimpleBlockingClient.java)
- [Simple blocking server](src/test/scala/tlschannel/example/SimpleBlockingServer.java)

### Non-blocking

Standard ByteChannel instances communicate the fact that operations would block—and so that they should be retried when the channel is ready—returning zero. However, as TLS handshakes happen transparently and involve multiple messages from each side, both a read and a write operation could be blocked waiting for either a read (byte available) or a write (buffer space available) in the underlying socket. That is, some way to distinguish between read- or write-related blocking is needed.

Ideally, a more complex return type would suffice—not merely an `int` but some object including more information. For instance, OpenSSL uses special error codes for these conditions: `SSL_ERROR_WANT_READ` and `SSL_ERROR_WANT_WRITE`.

In the case of TLS Channel, it is in practice necessary to maintain compatibility with the existing ByteChannel interface. That's why an somewhat unorthodox approach is used: when the operation would block, special exceptions are thrown: [NeedsReadException](https://oss.sonatype.org/service/local/repositories/releases/archive/com/github/marianobarrios/tls-channel/0.1.0/tls-channel-0.1.0-javadoc.jar/!/index.html?tlschannel/NeedsReadException.html) and [NeedsWriteException](https://oss.sonatype.org/service/local/repositories/releases/archive/com/github/marianobarrios/tls-channel/0.1.0/tls-channel-0.1.0-javadoc.jar/!/index.html?tlschannel/NeedsWriteException.html), meaning that the operation should be retried when the underlying channel is ready for reading or writing, respectively. 

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

Complete example: [non-blocking server](src/test/scala/tlschannel/example/NonBlockingServer.java)

### Off-loop tasks

Selector loops work under the assumption that they don't (mostly) block. This is  enough when it is possible to have as many loops as CPU cores. However, Java selectors don't work very well with multiple threads, requiring complicated synchronization; this leads to them being used almost universally from a single thread. 

A single IO thread is in general enough for plaintext connections. But TLS can be CPU-intensive, in particular asymmetric cryptography when establishing sessions. Fortunately, SSLEngine encapsulates those, returning [Runnable](https://docs.oracle.com/javase/8/docs/api/java/lang/Runnable.html) objects that the client code can run in any thread. TLS Channel can be configured to either run those as part of IO operations (that is, in-thread)—the default behavior—or not, letting the calling code handle them. The latter option should be enabled at construction time:
 
```java
TlsChannel tlsChannel = ServerTlsChannel
    .newBuilder(rawChannel, sslContext)
    .withRunTasks(false)
    .build();
```

An exception ([NeedsTaskException](https://oss.sonatype.org/service/local/repositories/releases/archive/com/github/marianobarrios/tls-channel/0.1.0/tls-channel-0.1.0-javadoc.jar/!/index.html?tlschannel/NeedsTaskException.html)) is then used to communicate that a task is ready to run. (Using an exception is needed for the same reasons explained in the previous section):

```java
try {
    int c = tlsChannel.read(buffer);
    ...
} catch ...
} catch (NeedsTaskException e) {
    taskExecutor.execute(() -> {
        e.getTask().run();
        // when the task finished, add it the the ready-set
        // taskReadyKeys should be a concurrent set that shoud be checked 
        // and emptied as part of the selector loop
        taskReadyKeys.add(key);
        selector.wakeup(); // unblock the selector
    });
}
```

Complete example: [non-blocking server with off-loop tasks](src/test/scala/tlschannel/example/NonBlockingServerWithOffLoopTasks.java)

### Server Name Indication - server side

The [Server Name Indication](https://tools.ietf.org/html/rfc6066#page-6) is a special TLS extension designed to solve a chicken-and-egg situation between the certificate offered by the server (depending on the host required by the client for multi-host servers) and the host name sent by client in HTTP request headers (necessarily after the connection is established). The extension allows the client to anticipate the required host in the ClientHello message.

Java added support for SNI in version 7. The feature can be accessed using the [SSLParameters](https://docs.oracle.com/javase/8/docs/api/javax/net/ssl/SSLParameters.html) class. Sadly, this only works for the client side. For the server, the class allows only to accept or reject connections based on the host name, not to choose the certificate offered. 

In TLS Channel, to use SNI-based selection of the SSLContext, a different builder factory method exists, receiving instances of [SniSslContextFactory](https://oss.sonatype.org/service/local/repositories/releases/archive/com/github/marianobarrios/tls-channel/0.1.0/tls-channel-0.1.0-javadoc.jar/!/index.html?tlschannel/SniSslContextFactory.html).

```java
SniSslContextFactory contextFactory = (Optional<SNIServerName> sniServerName) -> {
    Optional<SSLContext> ret = ...
    return ret;
};
TlsChannel tlsChannel = ServerTlsChannel
    .newBuilder(rawChannel, contextFactory)
    .build();
```

Complete example: [SNI-aware server](src/test/scala/tlschannel/example/SniBlockingServer.java)

## Buffers

TLS Channel uses buffers for its operation. Every channel uses at least two "encrypted" buffers that hold ciphertext, one for reading from the underlying channel and other for writing to it. Additionally, a third buffer may be needed for read operations when the user-supplied buffer is smaller than the minimum SSLEngine needs for placing the decrypted bytes.

All buffers are created from optionally user-supplied factories (instances of [BufferAllocator](https://oss.sonatype.org/service/local/repositories/releases/archive/com/github/marianobarrios/tls-channel/0.1.0/tls-channel-0.1.0-javadoc.jar/!/index.html?tlschannel/BufferAllocator.html)). It is also possible to supply different allocators for plain and ciphertext. For example:

```java
TlsChannel tlsChannel = ServerTlsChannel
    .newBuilder(rawChannel, sslContext)
    .withPlainBufferAllocator(new HeapBufferAllocator())
    .withEncryptedBufferAllocator(new DirectBufferAllocator())
    .build();
```

This is indeed the default behavior. The rationale for the encrypted buffers is that, in the most common use case, the underlying channel is a [SocketChannel](https://docs.oracle.com/javase/8/docs/api/java/nio/channels/SocketChannel.html). This channel actually does native IO operations, which are generally faster using direct buffers.

The plain buffers are not involved in IO, and so standard heap allocation is used by default.

### Zeroing

Buffers containing plain text are always immediately zeroed after the bytes are returned.

### Buffer release

TLS Channel supports opportunistic buffer release, a similar feature to OpenSSL's `SSL_MODE_RELEASE_BUFFERS` option. If, after any operation, a buffers does not contain any bytes pending, it is released back to the pool. This feature can reduce memory consumption dramatically in the case of long-lived idle connections, which tend to happen when implementing server-side HTTP.
 
 The option is enabled by default, and could be disabled if desired:
 
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

TLS Channel requires Java 8.

### Size and Dependencies

The library has only one dependency: [SLF4J](https://www.slf4j.org/). The main jar file size is below 50 KB.

### Logging

The library uses [SLF4J](https://www.slf4j.org/) for logging, which is the most widely used pluggable logging framework for the JVM. As a policy, _all_ logging event emitted are at `TRACE` level, which is below the default threshold in most logging implementations and thus completely silent by default.

## Similar efforts

- [NIO SSL](https://github.com/baswerc/niossl) is a simple library with a similar purpose, written by Corey Baswell.
- [sslfacade](https://github.com/kashifrazzaqui/sslfacade) is an attempt to offer a more reasonable interface than SSLEngine, written by Kashif Razzaqui.
- [sslengine.example](https://github.com/alkarn/sslengine.example) shows how to use SSLEngine, wrapping it in custom classes. It was written by Alex Karnezis.

## Credits

This work is based on a preliminary implementation by [Claudio Martinez](https://github.com/cldmartinez/) and [Mariano Barrios](https://github.com/marianobarrios/) at [Despegar](https://github.com/despegar).
