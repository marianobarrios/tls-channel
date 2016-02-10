package tlschannel

import com.typesafe.scalalogging.slf4j.StrictLogging
import javax.net.ssl.SSLContext

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
  
  val annonCiphers = SSLContext.getDefault.createSSLEngine().getSupportedCipherSuites
    // Java 8 disabled SSL through another mechanism, ignore that protocol here, to avoid errors 
    .filter(_.startsWith("TLS_"))
    // not using authentication
    .filter(_.contains("_anon_"))
  
}