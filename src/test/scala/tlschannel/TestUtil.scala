package tlschannel

import scala.util.control.NonFatal
import com.typesafe.scalalogging.slf4j.StrictLogging

object TestUtil extends StrictLogging {

  def cannotFail(msg: String)(thunk: => Unit) {
    try thunk
    catch {
      case NonFatal(e) =>
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

}