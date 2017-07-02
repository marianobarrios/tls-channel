package tlschannel;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * A decorating {@link BufferAllocator} that keeps statistics.
 */
public class TrackingAllocator implements BufferAllocator {

    private BufferAllocator impl;

    private LongAdder bytesAllocatedAdder = new LongAdder();
    private LongAdder bytesDeallocatedAdder = new LongAdder();
    private LongAdder currentAllocationSizeAdder = new LongAdder();
    private LongAccumulator maxAllocationSizeAcc = new LongAccumulator(Math::max, Long.MIN_VALUE);

    private LongAdder buffersAllocatedAdder = new LongAdder();
    private LongAdder buffersDeallocatedAdder = new LongAdder();

    public TrackingAllocator(BufferAllocator impl) {
        this.impl = impl;
    }

    public ByteBuffer allocate(int size) {
        bytesAllocatedAdder.add(size);
        currentAllocationSizeAdder.add(size);
        buffersAllocatedAdder.increment();
        return impl.allocate(size);
    }

    public void free(ByteBuffer buffer) {
        int size = buffer.capacity();
        bytesDeallocatedAdder.add(size);
        maxAllocationSizeAcc.accumulate(currentAllocationSizeAdder.longValue());
        currentAllocationSizeAdder.add(-size);
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
        return currentAllocationSizeAdder.longValue();
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
