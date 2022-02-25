package tlschannel;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;

import tlschannel.util.DirectBufferDeallocator;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class TlsChannelAndroidTests {
    @Test
    // Differential confirmation, 0d546f2b7562bd95dd52f7f0418f6bab0bc5e859 vs 31c939e5334b6dc7306346e9ee2407fc75416bfa
    public void testDirectBufferDeallocator() {
        DirectBufferDeallocator deallocator = new DirectBufferDeallocator();
        ByteBuffer buffer = ByteBuffer.allocateDirect(1000);
        deallocator.deallocate(buffer);
    }
}