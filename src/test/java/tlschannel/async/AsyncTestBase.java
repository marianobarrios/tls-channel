package tlschannel.async;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

interface AsyncTestBase {

    default void printChannelGroupStatus(AsynchronousTlsChannelGroup channelGroup) {
        System.out.println("channel group:");
        System.out.printf("  selection cycles: %8d\n", channelGroup.getSelectionCount());
        System.out.printf("  started reads:    %8d\n", channelGroup.getStartedReadCount());
        System.out.printf("  successful reads: %8d\n", channelGroup.getSuccessfulReadCount());
        System.out.printf("  failed reads:     %8d\n", channelGroup.getFailedReadCount());
        System.out.printf("  cancelled reads:  %8d\n", channelGroup.getCancelledReadCount());
        System.out.printf("  started writes:   %8d\n", channelGroup.getStartedWriteCount());
        System.out.printf("  successful write: %8d\n", channelGroup.getSuccessfulWriteCount());
        System.out.printf("  failed writes:    %8d\n", channelGroup.getFailedWriteCount());
        System.out.printf("  cancelled writes: %8d\n", channelGroup.getCancelledWriteCount());
    }

    default void shutdownChannelGroup(AsynchronousTlsChannelGroup group) {
        group.shutdown();
        try {
            boolean terminated = group.awaitTermination(100, TimeUnit.MILLISECONDS);
            assertTrue(terminated);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    default void assertChannelGroupConsistency(AsynchronousTlsChannelGroup group) {
        // give time to adders to converge
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        assertEquals(0, group.getCurrentRegistrationCount());
        assertEquals(0, group.getCurrentReadCount());
        assertEquals(0, group.getCurrentWriteCount());
        assertEquals(
                group.getCancelledReadCount() + group.getSuccessfulReadCount() + group.getFailedReadCount(),
                group.getStartedReadCount());
        assertEquals(
                group.getCancelledWriteCount() + group.getSuccessfulWriteCount() + group.getFailedWriteCount(),
                group.getStartedWriteCount());
    }
}
