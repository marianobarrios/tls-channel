package tlschannel

import com.typesafe.scalalogging.slf4j.StrictLogging
import javax.net.ssl.SSLContext
import java.util.function.Consumer

object TestUtil extends StrictLogging {

  def cannotFail(msg: String)(thunk: => Unit) {
    try thunk
    catch {
      case e: Throwable =>
        val lastMessage = "An essential thread failed unexpectedly, terminating process: " + msg
        logger.error(lastMessage, e)
        System.err.println(lastMessage)
        e.printStackTrace() // we are committing suicide, assure the reason gets through  
        Thread.sleep(1000) // give the process some time for flushing logs
        System.exit(1)
    }
  }

  def time[A](thunk: => A): (A, Long) = {
    val start = System.nanoTime()
    val res = thunk
    val time = (System.nanoTime() - start) / 1000
    (res, time)
  }

  implicit def functionToRunnable(fn: () => Unit): Runnable = {
    new Runnable {
      def run() = fn()
    }
  }
  
  implicit def fnToConsumer[A](fn: A => Unit): Consumer[A] = {
    new Consumer[A] {
      def accept(a: A) = fn(a)
    }
  }
  
  implicit def fnToFunction[A, B](fn: A => B): java.util.function.Function[A, B] = {
    new java.util.function.Function[A, B] {
      def apply(a: A): B = fn(a)
    }
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

}