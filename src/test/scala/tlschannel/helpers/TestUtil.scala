package tlschannel.helpers

import java.time.Duration

import com.typesafe.scalalogging.StrictLogging
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConversions._

object TestUtil extends StrictLogging {

  def cannotFail(thunk: => Unit) {
    try thunk
    catch {
      case e: Throwable =>
        val lastMessage = s"An essential thread (${Thread.currentThread().getName}) failed unexpectedly, terminating process"
        logger.error(lastMessage, e)
        System.err.println(lastMessage)
        e.printStackTrace() // we are committing suicide, assure the reason gets through  
        Thread.sleep(1000) // give the process some time for flushing logs
        System.exit(1)
    }
  }

  def time[A](thunk: => A): (A, Duration) = {
    val start = System.nanoTime()
    val res = thunk
    val time = Duration.ofNanos(System.nanoTime() - start)
    (res, time)
  }

  def time[A](thunk: => Unit): Duration = {
    val start = System.nanoTime()
    thunk
    Duration.ofNanos(System.nanoTime() - start)
  }

  implicit class StreamWithTakeWhileInclusive[A](stream: Stream[A]) {
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

  implicit class IterableWithForany[A](iterable: Iterable[A]) {
    def forany(p: A => Boolean): Boolean = {
      !iterable.forall(a => !p(a))
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

  /**
   * @param f the function to memoize
   * @tparam I input to f
   * @tparam K the keys we should use in cache instead of I
   * @tparam O output of f
   */
  case class Memo[I <% K, K, O](f: I => O) extends (I => O) {
    val cache = new ConcurrentHashMap[K, O]
    override def apply(x: I) = cache.getOrElseUpdate(x, f(x))
  }
  
}