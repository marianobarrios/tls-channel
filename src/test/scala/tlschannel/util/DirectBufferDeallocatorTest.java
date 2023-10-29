package tlschannel.util;

import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;

@TestInstance(Lifecycle.PER_CLASS)
public class DirectBufferDeallocatorTest {

    @Test
    public void testDirectBufferDeallocator() {
        var deallocator = new DirectBufferDeallocator();
        var buffer = ByteBuffer.allocateDirect(1000);
        deallocator.deallocate(buffer);
    }
}
