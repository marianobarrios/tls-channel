package tlschannel;

import android.Manifest;
import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.GrantPermissionRule;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.model.MultipleFailureException;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;

import tlschannel.util.DirectBufferDeallocator;

/**
 * Instrumented test, which will execute on an Android device.
 *
 * @see <a href="http://d.android.com/tools/testing">Testing documentation</a>
 */
@RunWith(AndroidJUnit4.class)
public class TlsChannelAndroidTests {
    @Rule
    public GrantPermissionRule mRuntimePermissionRule = GrantPermissionRule.grant(Manifest.permission.INTERNET);

    @Test
    // Differential confirmation, 0d546f2b7562bd95dd52f7f0418f6bab0bc5e859 vs 31c939e5334b6dc7306346e9ee2407fc75416bfa
    public void testDirectBufferDeallocator() {
        DirectBufferDeallocator deallocator = new DirectBufferDeallocator();
        ByteBuffer buffer = ByteBuffer.allocateDirect(1000);
        deallocator.deallocate(buffer);
    }

    @Test
    public void testClientServerExchange() throws Throwable {
        CountDownLatch cdl = new CountDownLatch(2);

        Throwable[] tServer = new Throwable[1];
        new Thread(() -> {
            System.out.println("--> Server thread");
            try {
                String response = SimpleBlockingServer.exchange(10992, "Server -> Client");
                System.out.println("Server rx " + response);
                assertEquals("Client -> Server", response);
            } catch (Throwable t) {
                tServer[0] = t;
                cdl.countDown();
            }
            cdl.countDown();
            System.out.println("<-- Server thread");
        }).start();

        Thread.sleep(1000); // In case the server takes a bit to start

        Throwable[] tClient = new Throwable[1];
        new Thread(() -> {
            System.out.println("--> Client thread");
            try {
                String response = SimpleBlockingClient.exchange("127.0.0.1", 10992, "Client -> Server");
                System.out.println("Client rx " + response);
                assertEquals("Server -> Client", response);
            } catch (Throwable t) {
                tClient[0] = t;
                cdl.countDown();
            }
            cdl.countDown();
            System.out.println("<-- Client thread");
        }).start();

        cdl.await();

        if (tClient[0] != null) {
            if (tServer[0] != null) {
                throw new MultipleFailureException(Arrays.asList(tClient[0], tServer[0]));
            }
            throw tClient[0];
        }
        if (tServer[0] != null) {
            throw tServer[0];
        }
    }
}