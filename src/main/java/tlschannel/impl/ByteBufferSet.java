package tlschannel.impl;

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;


import tlschannel.TlsChannel;

public interface ByteBufferSet
{
    long remaining();

    int putRemaining(ByteBuffer from);

    ByteBufferSet put(ByteBuffer from, int length);

    int getRemaining(ByteBuffer dst);

    ByteBufferSet get(ByteBuffer dst, int length);

    boolean hasRemaining();

    boolean isReadOnly();

    SSLEngineResult unwrap(SSLEngine engine, ByteBuffer buffer) throws SSLException;

    SSLEngineResult wrap(SSLEngine engine, ByteBuffer buffer) throws SSLException;

    long read(TlsChannel tlsChannel) throws IOException;
}
