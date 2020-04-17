package tlschannel;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.Channel;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import tlschannel.impl.ByteBufferSet;
import tlschannel.impl.ImmutableByteBufferSet;
import tlschannel.impl.MutableSingleBufferSet;
import tlschannel.impl.TlsChannelImpl;

/** A client-side {@link TlsChannel}. */
public class ClientTlsChannel implements TlsChannel {

  /** Builder of {@link ClientTlsChannel} */
  public static class Builder extends TlsChannelBuilder<Builder> {

    private final Supplier<SSLEngine> sslEngineFactory;

    private Builder(ByteChannel underlying, SSLEngine sslEngine) {
      super(underlying);
      this.sslEngineFactory = () -> sslEngine;
    }

    private Builder(ByteChannel underlying, SSLContext sslContext) {
      super(underlying);
      this.sslEngineFactory = () -> defaultSSLEngineFactory(sslContext);
    }

    @Override
    Builder getThis() {
      return this;
    }

    public ClientTlsChannel build() {
      return new ClientTlsChannel(
          underlying,
          sslEngineFactory.get(),
          sessionInitCallback,
          runTasks,
          plainBufferAllocator,
          encryptedBufferAllocator,
          releaseBuffers,
          waitForCloseConfirmation);
    }
  }

  private static SSLEngine defaultSSLEngineFactory(SSLContext sslContext) {
    SSLEngine engine = sslContext.createSSLEngine();
    engine.setUseClientMode(true);
    return engine;
  }

  /**
   * Create a new {@link Builder}, configured with a underlying {@link Channel} and a fixed {@link
   * SSLEngine}.
   *
   * @param underlying a reference to the underlying {@link ByteChannel}
   * @param sslEngine the engine to use with this channel
   * @return the new builder
   */
  public static Builder newBuilder(ByteChannel underlying, SSLEngine sslEngine) {
    return new Builder(underlying, sslEngine);
  }

  /**
   * Create a new {@link Builder}, configured with a underlying {@link Channel} and a {@link
   * SSLContext}.
   *
   * @param underlying a reference to the underlying {@link ByteChannel}
   * @param sslContext a context to use with this channel, it will be used to create a client {@link
   *     SSLEngine}.
   * @return the new builder
   */
  public static Builder newBuilder(ByteChannel underlying, SSLContext sslContext) {
    return new Builder(underlying, sslContext);
  }

  private final ByteChannel underlying;
  private final TlsChannelImpl impl;

  private final MutableSingleBufferSet mutableSingleBufferSetRead = new MutableSingleBufferSet();
  private final MutableSingleBufferSet mutableSingleBufferSetWrite = new MutableSingleBufferSet();

  private ClientTlsChannel(
      ByteChannel underlying,
      SSLEngine engine,
      Consumer<SSLSession> sessionInitCallback,
      boolean runTasks,
      BufferAllocator plainBufAllocator,
      BufferAllocator encryptedBufAllocator,
      boolean releaseBuffers,
      boolean waitForCloseNotifyOnClose) {
    if (!engine.getUseClientMode())
      throw new IllegalArgumentException("SSLEngine must be in client mode");
    this.underlying = underlying;
    TrackingAllocator trackingPlainBufAllocator = new TrackingAllocator(plainBufAllocator);
    TrackingAllocator trackingEncryptedAllocator = new TrackingAllocator(encryptedBufAllocator);
    impl =
        new TlsChannelImpl(
            underlying,
            underlying,
            engine,
            Optional.empty(),
            sessionInitCallback,
            runTasks,
            trackingPlainBufAllocator,
            trackingEncryptedAllocator,
            releaseBuffers,
            waitForCloseNotifyOnClose);
  }

  @Override
  public ByteChannel getUnderlying() {
    return underlying;
  }

  @Override
  public SSLEngine getSslEngine() {
    return impl.engine();
  }

  @Override
  public Consumer<SSLSession> getSessionInitCallback() {
    return impl.getSessionInitCallback();
  }

  @Override
  public TrackingAllocator getPlainBufferAllocator() {
    return impl.getPlainBufferAllocator();
  }

  @Override
  public TrackingAllocator getEncryptedBufferAllocator() {
    return impl.getEncryptedBufferAllocator();
  }

  @Override
  public boolean getRunTasks() {
    return impl.getRunTasks();
  }

  @Override
  public long read(ByteBuffer[] dstBuffers, int offset, int length) throws IOException {
    ByteBufferSet dest = new ImmutableByteBufferSet(dstBuffers, offset, length);
    return read(dest);
  }

  @Override
  public long read(ByteBuffer[] dstBuffers) throws IOException {
    return read(dstBuffers, 0, dstBuffers.length);
  }

  @Override
  public int read(ByteBuffer dstBuffer) throws IOException {
    return (int) read(mutableSingleBufferSetRead.wrap(dstBuffer));
  }

  @Override
  public long write(ByteBuffer[] srcBuffers, int offset, int length) throws IOException {
    ImmutableByteBufferSet source = new ImmutableByteBufferSet(srcBuffers, offset, length);
    return write(source);
  }

  @Override
  public long write(ByteBuffer[] outs) throws IOException {
    return write(outs, 0, outs.length);
  }

  @Override
  public int write(ByteBuffer srcBuffer) throws IOException {
    return (int) write(mutableSingleBufferSetWrite.wrap(srcBuffer));
  }

  @Override
  public void renegotiate() throws IOException {
    impl.renegotiate();
  }

  @Override
  public void handshake() throws IOException {
    impl.handshake();
  }

  @Override
  public void close() throws IOException {
    impl.close();
  }

  @Override
  public boolean isOpen() {
    return impl.isOpen();
  }

  @Override
  public boolean shutdown() throws IOException {
    return impl.shutdown();
  }

  @Override
  public boolean shutdownReceived() {
    return impl.shutdownReceived();
  }

  @Override
  public boolean shutdownSent() {
    return impl.shutdownSent();
  }

  private long read(final ByteBufferSet dest) throws IOException {
    TlsChannelImpl.checkReadBuffer(dest);
    return impl.read(dest);
  }

  private long write(final ByteBufferSet source) throws IOException {
    return impl.write(source);
  }
}
