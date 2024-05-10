/**
 * TLS Channel is a library that implements a ByteChannel interface to a TLS (Transport Layer
 * Security) connection. The library delegates all cryptographic operations to the standard Java TLS
 * implementation: SSLEngine; effectively hiding it behind an easy-to-use streaming API, that allows
 * to secure JVM applications with minimal added complexity.
 *
 * <p>In other words, a simple library that allows the programmer to have TLS using the same
 * standard socket API used for plaintext, just like OpenSSL does for C, only for Java, filling a
 * specially painful missing feature of the standard Java library.
 */
package tlschannel;
