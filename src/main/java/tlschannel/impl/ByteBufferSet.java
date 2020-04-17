package tlschannel.impl;

import java.nio.ByteBuffer;

public interface ByteBufferSet
{
    ByteBuffer[] getBuffers();

    ByteBuffer getBuffer();

    default int numberOfBuffersRemaining()
    {
        return getLength() - getOffset();
    }

    int getOffset();

    int getLength();

    long remaining();

    int putRemaining(ByteBuffer from);

    ByteBufferSet put(ByteBuffer from, int length);

    int getRemaining(ByteBuffer dst);

    ByteBufferSet get(ByteBuffer dst, int length);

    boolean hasRemaining();

    boolean isReadOnly();
}
