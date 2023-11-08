package tlschannel.helpers;

import java.util.logging.Level;
import java.util.logging.Logger;

public class TestJavaUtil {

    private static final Logger logger = Logger.getLogger(TestJavaUtil.class.getName());

    public static void cannotFail(Runnable runnable) {
        try {
            runnable.run();
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
    }
}
