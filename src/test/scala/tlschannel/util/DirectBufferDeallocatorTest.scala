package tlschannel.util

import java.nio.ByteBuffer

import org.scalatest.{FunSuite, Matchers}

class DirectBufferDeallocatorTest extends FunSuite with Matchers {

  test("test direct buffer deallocator") {
    val deallocator = new DirectBufferDeallocator
    val buffer = ByteBuffer.allocateDirect(1000)
    deallocator.deallocate(buffer)
  }

}
