package tlschannel.impl;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ReadOnlyBufferException;
import org.junit.jupiter.api.Test;

public class ByteBufferSetTest {

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    @Test
    public void constructorNullArrayThrows() {
        assertThrows(NullPointerException.class, () -> new ByteBufferSet(null, 0, 0));
    }

    @Test
    public void constructorNullElementThrows() {
        ByteBuffer[] array = {ByteBuffer.allocate(4), null};
        assertThrows(NullPointerException.class, () -> new ByteBufferSet(array, 0, 2));
    }

    @Test
    public void constructorOffsetOutOfBoundsThrows() {
        ByteBuffer[] array = {ByteBuffer.allocate(4)};
        assertThrows(IndexOutOfBoundsException.class, () -> new ByteBufferSet(array, 2, 0));
    }

    @Test
    public void constructorLengthOutOfBoundsThrows() {
        ByteBuffer[] array = {ByteBuffer.allocate(4)};
        assertThrows(IndexOutOfBoundsException.class, () -> new ByteBufferSet(array, 0, 2));
    }

    @Test
    public void constructorSingleBuffer() {
        ByteBuffer buf = ByteBuffer.allocate(8);
        ByteBufferSet set = new ByteBufferSet(buf);
        assertEquals(0, set.offset);
        assertEquals(1, set.length);
        assertSame(buf, set.array[0]);
    }

    @Test
    public void constructorArrayWithOffsetAndLength() {
        ByteBuffer a = ByteBuffer.allocate(4);
        ByteBuffer b = ByteBuffer.allocate(4);
        ByteBuffer c = ByteBuffer.allocate(4);
        ByteBufferSet set = new ByteBufferSet(new ByteBuffer[] {a, b, c}, 1, 2);
        assertEquals(1, set.offset);
        assertEquals(2, set.length);
    }

    // -------------------------------------------------------------------------
    // remaining()
    // -------------------------------------------------------------------------

    @Test
    public void remainingEmptyBuffer() {
        ByteBuffer buf = ByteBuffer.allocate(8);
        buf.position(8);
        assertEquals(0, new ByteBufferSet(buf).remaining());
    }

    @Test
    public void remainingSingleBuffer() {
        assertEquals(8, new ByteBufferSet(ByteBuffer.allocate(8)).remaining());
    }

    @Test
    public void remainingMultipleBuffers() {
        ByteBuffer a = ByteBuffer.allocate(4);
        ByteBuffer b = ByteBuffer.allocate(6);
        assertEquals(10, new ByteBufferSet(new ByteBuffer[] {a, b}).remaining());
    }

    @Test
    public void remainingRespectsOffset() {
        ByteBuffer a = ByteBuffer.allocate(4);
        ByteBuffer b = ByteBuffer.allocate(6);
        // only buffer b is in the active window
        assertEquals(6, new ByteBufferSet(new ByteBuffer[] {a, b}, 1, 1).remaining());
    }

    // -------------------------------------------------------------------------
    // position()
    // -------------------------------------------------------------------------

    @Test
    public void positionInitiallyZero() {
        ByteBuffer a = ByteBuffer.allocate(4);
        ByteBuffer b = ByteBuffer.allocate(4);
        assertEquals(0, new ByteBufferSet(new ByteBuffer[] {a, b}).position());
    }

    @Test
    public void positionSumsAcrossBuffers() {
        ByteBuffer a = ByteBuffer.allocate(4);
        ByteBuffer b = ByteBuffer.allocate(4);
        a.position(3);
        b.position(2);
        assertEquals(5, new ByteBufferSet(new ByteBuffer[] {a, b}).position());
    }

    // -------------------------------------------------------------------------
    // hasRemaining()
    // -------------------------------------------------------------------------

    @Test
    public void hasRemainingWhenNotEmpty() {
        assertTrue(new ByteBufferSet(ByteBuffer.allocate(1)).hasRemaining());
    }

    @Test
    public void hasRemainingFalseWhenExhausted() {
        ByteBuffer buf = ByteBuffer.allocate(4);
        buf.position(4);
        assertFalse(new ByteBufferSet(buf).hasRemaining());
    }

    // -------------------------------------------------------------------------
    // isReadOnly()
    // -------------------------------------------------------------------------

    @Test
    public void isReadOnlyFalseForWritableBuffers() {
        ByteBuffer a = ByteBuffer.allocate(4);
        ByteBuffer b = ByteBuffer.allocate(4);
        assertFalse(new ByteBufferSet(new ByteBuffer[] {a, b}).isReadOnly());
    }

    @Test
    public void isReadOnlyTrueWhenAnyBufferIsReadOnly() {
        ByteBuffer writable = ByteBuffer.allocate(4);
        ByteBuffer readOnly = ByteBuffer.allocate(4).asReadOnlyBuffer();
        assertTrue(new ByteBufferSet(new ByteBuffer[] {writable, readOnly}).isReadOnly());
    }

    @Test
    public void isReadOnlyIgnoresBuffersOutsideWindow() {
        ByteBuffer readOnly = ByteBuffer.allocate(4).asReadOnlyBuffer();
        ByteBuffer writable = ByteBuffer.allocate(4);
        // readOnly is at index 0, outside the active window [1, 2)
        assertFalse(new ByteBufferSet(new ByteBuffer[] {readOnly, writable}, 1, 1).isReadOnly());
    }

    // -------------------------------------------------------------------------
    // put()
    // -------------------------------------------------------------------------

    @Test
    public void putAdvancesSourcePosition() {
        ByteBuffer from = ByteBuffer.wrap(new byte[] {1, 2, 3, 4});
        new ByteBufferSet(ByteBuffer.allocate(4)).put(from, 2);
        assertEquals(2, from.remaining());
    }

    @Test
    public void putZeroBytesLeavesSourceUnchanged() {
        ByteBuffer from = ByteBuffer.wrap(new byte[] {1, 2});
        new ByteBufferSet(ByteBuffer.allocate(4)).put(from, 0);
        assertEquals(2, from.remaining());
    }

    @Test
    public void putThrowsWhenSourceHasInsufficientBytes() {
        ByteBuffer from = ByteBuffer.wrap(new byte[] {1});
        ByteBufferSet set = new ByteBufferSet(ByteBuffer.allocate(4));
        assertThrows(IllegalArgumentException.class, () -> set.put(from, 4));
    }

    @Test
    public void putThrowsWhenDestinationHasInsufficientCapacity() {
        ByteBuffer from = ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5});
        ByteBufferSet set = new ByteBufferSet(ByteBuffer.allocate(2));
        assertThrows(IllegalArgumentException.class, () -> set.put(from, 5));
    }

    @Test
    public void putThrowsOnReadOnly() {
        ByteBuffer from = ByteBuffer.wrap(new byte[] {1, 2});
        ByteBufferSet set = new ByteBufferSet(ByteBuffer.allocate(4).asReadOnlyBuffer());
        assertThrows(ReadOnlyBufferException.class, () -> set.put(from, 2));
    }

    // -------------------------------------------------------------------------
    // putRemaining()
    // -------------------------------------------------------------------------

    @Test
    public void putRemainingReturnsNumberOfBytesCopied() {
        ByteBuffer from = ByteBuffer.wrap(new byte[] {1, 2, 3});
        int written = new ByteBufferSet(ByteBuffer.allocate(3)).putRemaining(from);
        assertEquals(3, written);
    }

    @Test
    public void putRemainingExhaustsSource() {
        ByteBuffer from = ByteBuffer.wrap(new byte[] {1, 2, 3});
        new ByteBufferSet(ByteBuffer.allocate(3)).putRemaining(from);
        assertFalse(from.hasRemaining());
    }

    @Test
    public void putAcrossTwoBuffers() {
        ByteBuffer from = ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5, 6});
        ByteBuffer dst1 = ByteBuffer.allocate(3);
        ByteBuffer dst2 = ByteBuffer.allocate(3);
        assertDoesNotThrow(() -> new ByteBufferSet(new ByteBuffer[] {dst1, dst2}).put(from, 6));
    }

    @Test
    public void putRemainingStopsWhenSourceExhausted() {
        ByteBuffer from = ByteBuffer.wrap(new byte[] {1, 2});
        ByteBuffer dst1 = ByteBuffer.allocate(4);
        ByteBuffer dst2 = ByteBuffer.allocate(4);
        int written = new ByteBufferSet(new ByteBuffer[] {dst1, dst2}).putRemaining(from);
        assertEquals(2, written);
    }

    @Test
    public void putRemainingThrowsOnReadOnly() {
        ByteBuffer from = ByteBuffer.wrap(new byte[] {1, 2});
        ByteBufferSet set = new ByteBufferSet(ByteBuffer.allocate(4).asReadOnlyBuffer());
        assertThrows(ReadOnlyBufferException.class, () -> set.putRemaining(from));
    }

    // -------------------------------------------------------------------------
    // get
    // -------------------------------------------------------------------------

    @Test
    public void getFromSingleBuffer() {
        ByteBuffer dst = ByteBuffer.allocate(4);
        new ByteBufferSet(ByteBuffer.wrap(new byte[] {1, 2, 3, 4})).get(dst, 4);

        assertBufferEquals(new byte[]{1, 2, 3, 4}, dst);
    }

    @Test
    public void getSpanningMultipleBuffers() {
        ByteBuffer src1 = ByteBuffer.wrap(new byte[] {1, 2, 3});
        ByteBuffer src2 = ByteBuffer.wrap(new byte[] {4, 5, 6});
        ByteBuffer dst = ByteBuffer.allocate(6);
        new ByteBufferSet(new ByteBuffer[] {src1, src2}).get(dst, 6);

        assertBufferEquals(new byte[]{1, 2, 3, 4, 5, 6}, dst);
    }

    @Test
    public void getPartialLength() {
        ByteBuffer src = ByteBuffer.wrap(new byte[] {1, 2, 3, 4});
        ByteBuffer dst = ByteBuffer.allocate(4);
        new ByteBufferSet(src).get(dst, 2);

        assertBufferEquals(new byte[]{1, 2}, dst);
    }

    @Test
    public void getThrowsWhenSourceHasInsufficientBytes() {
        ByteBuffer src = ByteBuffer.wrap(new byte[] {1});
        ByteBufferSet set = new ByteBufferSet(src);
        assertThrows(IllegalArgumentException.class, () -> set.get(ByteBuffer.allocate(4), 4));
    }

    @Test
    public void getThrowsWhenDestinationHasInsufficientCapacity() {
        ByteBufferSet set = new ByteBufferSet(ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5}));
        assertThrows(IllegalArgumentException.class, () -> set.get(ByteBuffer.allocate(2), 5));
    }

    // -------------------------------------------------------------------------
    // getRemaining(ByteBuffer dst)
    // -------------------------------------------------------------------------

    @Test
    public void getRemainingAllBytes() {
        ByteBuffer dst = ByteBuffer.allocate(3);
        int read = new ByteBufferSet(ByteBuffer.wrap(new byte[] {1, 2, 3})).getRemaining(dst);

        assertEquals(3, read);
        assertBufferEquals(new byte[]{1, 2, 3}, dst);
    }

    @Test
    public void getRemainingStopsWhenDestinationFull() {
        ByteBuffer src1 = ByteBuffer.wrap(new byte[] {1, 2, 3});
        ByteBuffer src2 = ByteBuffer.wrap(new byte[] {4, 5, 6});
        ByteBuffer dst = ByteBuffer.allocate(2);
        int read = new ByteBufferSet(new ByteBuffer[] {src1, src2}).getRemaining(dst);

        assertEquals(2, read);
    }

    @Test
    public void getRemainingSpanningBuffers() {
        ByteBuffer src1 = ByteBuffer.wrap(new byte[] {1, 2});
        ByteBuffer src2 = ByteBuffer.wrap(new byte[] {3, 4, 5});
        ByteBuffer dst = ByteBuffer.allocate(5);
        int read = new ByteBufferSet(new ByteBuffer[] {src1, src2}).getRemaining(dst);

        assertEquals(5, read);
        assertBufferEquals(new byte[]{1, 2, 3, 4, 5}, dst);
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static void assertBufferEquals(byte[] expected, ByteBuffer actual) {
        ByteBuffer view = actual.duplicate(); // avoid flipping the original
        view.flip();
        assertEquals(ByteBuffer.wrap(expected), view);
    }

}
