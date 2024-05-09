package tlschannel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

@TestInstance(Lifecycle.PER_CLASS)
public class TestLogger {

    @Test
    public void logJdk() {
        System.out.println("java.version: " + System.getProperty("java.version"));
    }
}
