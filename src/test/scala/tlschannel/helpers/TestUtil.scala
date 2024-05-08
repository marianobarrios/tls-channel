package tlschannel.helpers

import java.util.SplittableRandom
import java.util.logging.Logger

object TestUtil {

  val logger = Logger.getLogger(TestUtil.getClass.getName)

  def removeAndCollect[A](iterator: java.util.Iterator[A]): Seq[A] = {
    val builder = Seq.newBuilder[A]
    while (iterator.hasNext) {
      builder += iterator.next()
      iterator.remove()
    }
    builder.result()
  }

  def nextBytes(random: SplittableRandom, bytes: Array[Byte]): Unit = {
    nextBytes(random, bytes, bytes.length)
  }

  def nextBytes(random: SplittableRandom, bytes: Array[Byte], len: Int): Unit = {
    var i = 0
    while (i < len) {
      var rnd = random.nextInt()
      var n = Math.min(len - i, Integer.SIZE / java.lang.Byte.SIZE)
      while (n > 0) {
        bytes(i) = rnd.toByte
        rnd >>= java.lang.Byte.SIZE
        n -= 1
        i += 1
      }
    }
  }
}
