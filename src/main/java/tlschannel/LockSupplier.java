package tlschannel;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public interface LockSupplier {

    public default Lock newInitLock() {
        return new ReentrantLock();
    }

    public default Lock newReadLock() {
        return new ReentrantLock();
    }

    public default Lock newWriteLock() {
        return new ReentrantLock();
    }
}
