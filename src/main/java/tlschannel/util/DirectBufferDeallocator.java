package tlschannel.util;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Access to NIO sun.misc.Cleaner, allowing caller to deterministically deallocate a given
 * sun.nio.ch.DirectBuffer.
 */
public class DirectBufferDeallocator {

    private static final Logger logger = LoggerFactory.getLogger(DirectBufferDeallocator.class);

    private interface Deallocator {
        void free(ByteBuffer bb);
    }

    private static class Java8Deallocator implements Deallocator {

        /*
         * Getting instance of cleaner from buffer (sun.misc.Cleaner)
         */

        final Method cleanerAccessor;
        final Method clean;

        Java8Deallocator() {
            try {
                cleanerAccessor = Class.forName("sun.nio.ch.DirectBuffer").getMethod("cleaner");
                clean = Class.forName("sun.misc.Cleaner").getMethod("clean");
            } catch (NoSuchMethodException | ClassNotFoundException t) {
                throw new RuntimeException(t);
            }
        }

        @Override
        public void free(ByteBuffer bb) {
            try {
                clean.invoke(cleanerAccessor.invoke(bb));
            } catch (IllegalAccessException | InvocationTargetException t) {
                throw new RuntimeException(t);
            }
        }
    }

    private static class Java9Deallocator implements Deallocator {

        /*
         * Clean is of type jdk.internal.ref.Cleaner, but this type is not accessible, as it is not exported by default.
         * Using workaround through sun.misc.Unsafe.
         */

        final Object unsafe;
        final Method invokeCleaner;

        Java9Deallocator() {
            try {
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                // avoiding getUnsafe methods, as it is explicitly filtered out from reflection API
                Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
                theUnsafe.setAccessible(true);
                unsafe = theUnsafe.get(null);
                invokeCleaner = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
            } catch (NoSuchMethodException | ClassNotFoundException | IllegalAccessException | NoSuchFieldException t) {
                throw new RuntimeException(t);
            }
        }

        @Override
        public void free(ByteBuffer bb) {
            try {
                invokeCleaner.invoke(unsafe, bb);
            } catch (IllegalAccessException | InvocationTargetException t) {
                throw new RuntimeException(t);
            }
        }
    }

    private final Deallocator deallocator;

    public DirectBufferDeallocator() {
        if (Util.getJavaMajorVersion() >= 9) {
            deallocator = new Java9Deallocator();
            logger.debug("initialized direct buffer deallocator for java >= 9");
        } else {
            deallocator = new Java8Deallocator();
            logger.debug("initialized direct buffer deallocator for java < 9");
        }
    }

    public void deallocate(ByteBuffer buffer) {
        deallocator.free(buffer);
    }
}
