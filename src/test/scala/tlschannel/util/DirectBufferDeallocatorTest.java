package tlschannel.util

import org.junit.jupiter.api.{Test, TestInstance}
import org.junit.jupiter.api.TestInstance.Lifecycle

import java.nio.ByteBuffer

@TestInstance(Lifecycle.PER_CLASS)
class DirectBufferDeallocatorTest {

  @Test
  def testDirectBufferDeallocator(): Unit = {
    val deallocator = new DirectBufferDeallocator
    val buffer = ByteBuffer.allocateDirect(1000)
    deallocator.deallocate(buffer)
  }

}
