package tlschannel;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/** A decorating {@link BufferAllocator} that keeps statistics. */
public class TrackingAllocator implements BufferAllocator {

    private final BufferAllocator impl;

    private final LongAdder bytesAllocatedAdder = new LongAdder();
    private final LongAdder bytesDeallocatedAdder = new LongAdder();
    private final AtomicLong currentAllocationSize = new AtomicLong();
    private final LongAccumulator maxAllocationSizeAcc = new LongAccumulator(Math::max, 0);

    private final LongAdder buffersAllocatedAdder = new LongAdder();
    private final LongAdder buffersDeallocatedAdder = new LongAdder();

    public TrackingAllocator(BufferAllocator impl) {
        this.impl = impl;
    }

    public ByteBuffer allocate(int size) {
        bytesAllocatedAdder.add(size);
        currentAllocationSize.addAndGet(size);
        buffersAllocatedAdder.increment();
        return impl.allocate(size);
    }

    public void free(ByteBuffer buffer) {
        int size = buffer.capacity();
        bytesDeallocatedAdder.add(size);
        maxAllocationSizeAcc.accumulate(currentAllocationSize.longValue());
        currentAllocationSize.addAndGet(-size);
        buffersDeallocatedAdder.increment();
        impl.free(buffer);
    }

    public long bytesAllocated() {
        return bytesAllocatedAdder.longValue();
    }

    public long bytesDeallocated() {
        return bytesDeallocatedAdder.longValue();
    }

    public long currentAllocation() {
        return currentAllocationSize.longValue();
    }

    public long maxAllocation() {
        return maxAllocationSizeAcc.longValue();
    }

    public long buffersAllocated() {
        return buffersAllocatedAdder.longValue();
    }

    public long buffersDeallocated() {
        return buffersDeallocatedAdder.longValue();
    }
}
