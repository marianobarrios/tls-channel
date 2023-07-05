package tlschannel.helpers

import java.util.SplittableRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.{Level, Logger}
import scala.jdk.CollectionConverters._
import scala.util.control.ControlThrowable

object TestUtil {

  val logger = Logger.getLogger(TestUtil.getClass.getName)

  def cannotFail(thunk: => Unit): Unit = {
    try thunk
    catch {
      case r: ControlThrowable =>
      // pass
      case e: Throwable =>
        val lastMessage =
          s"An essential thread (${Thread.currentThread().getName}) failed unexpectedly, terminating process"
        logger.log(Level.SEVERE, lastMessage, e)
        System.err.println(lastMessage)
        e.printStackTrace() // we are committing suicide, assure the reason gets through
        Thread.sleep(1000) // give the process some time for flushing logs
        System.exit(1)
    }
  }

  implicit class LazyListWithTakeWhileInclusive[A](stream: LazyList[A]) {
    def takeWhileInclusive(p: A => Boolean) = {
      var done = false
      def newPredicate(a: A): Boolean = {
        if (done)
          return false
        if (!p(a))
          done = true
        true
      }
      stream.takeWhile(newPredicate)
    }
  }

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

  /** @param f
    *   the function to memoize
    * @tparam I
    *   input to f
    * @tparam O
    *   output of f
    */
  class Memo[I, O](f: I => O) extends (I => O) {
    val cache = new ConcurrentHashMap[I, O]
    override def apply(x: I) = cache.asScala.getOrElseUpdate(x, f(x))
  }
}
