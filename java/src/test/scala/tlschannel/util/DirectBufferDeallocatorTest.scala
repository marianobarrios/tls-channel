package tlschannel.util

import java.nio.ByteBuffer

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.Assertions

class DirectBufferDeallocatorTest extends AnyFunSuite with Assertions {

  test("test direct buffer deallocator") {
    val deallocator = new DirectBufferDeallocator
    val buffer = ByteBuffer.allocateDirect(1000)
    deallocator.deallocate(buffer)
  }

}
