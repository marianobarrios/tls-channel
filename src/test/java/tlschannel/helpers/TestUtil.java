package tlschannel.helpers;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

public class TestUtil {

    public static <A> Stream<A> removeAndCollect(Iterator<A> iterator) {
        List<A> builder = new ArrayList<>();
        while (iterator.hasNext()) {
            builder.add(iterator.next());
            iterator.remove();
        }
        return builder.stream();
    }

    @FunctionalInterface
    public interface ExceptionalRunnable {
        void run() throws Exception;
    }

    private static final Logger logger = Logger.getLogger(TestUtil.class.getName());

    public static void cannotFail(ExceptionalRunnable exceptionalRunnable) {
        cannotFailRunnable(exceptionalRunnable).run();
    }

    public static Runnable cannotFailRunnable(ExceptionalRunnable exceptionalRunnable) {
        return () -> {
            try {
                exceptionalRunnable.run();
            } catch (Throwable e) {
                String lastMessage = String.format(
                        "An essential thread (%s) failed unexpectedly, terminating process",
                        Thread.currentThread().getName());
                logger.log(Level.SEVERE, lastMessage, e);
                System.err.println(lastMessage);
                e.printStackTrace(); // we are committing suicide, assure the reason gets through
                try {
                    Thread.sleep(1000); // give the process some time for flushing logs
                } catch (InterruptedException ex) {
                    throw new RuntimeException(ex);
                }
                System.exit(1);
            }
        };
    }

    public static class Memo<I, O> {
        private final ConcurrentHashMap<I, O> cache = new ConcurrentHashMap<>();
        private final Function<I, O> f;

        public Memo(Function<I, O> f) {
            this.f = f;
        }

        public O apply(I i) {
            return cache.computeIfAbsent(i, f);
        }
    }

    public static void nextBytes(SplittableRandom random, byte[] bytes) {
        nextBytes(random, bytes, bytes.length);
    }

    public static void nextBytes(SplittableRandom random, byte[] bytes, int len) {
        int i = 0;
        while (i < len) {
            int rnd = random.nextInt();
            int n = Math.min(len - i, Integer.SIZE / java.lang.Byte.SIZE);
            while (n > 0) {
                bytes[i] = (byte) rnd;
                rnd >>= java.lang.Byte.SIZE;
                n -= 1;
                i += 1;
            }
        }
    }
}
