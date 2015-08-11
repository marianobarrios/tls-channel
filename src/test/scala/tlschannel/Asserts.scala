package tlschannel

import org.scalatest.Matchers

trait Asserts extends Matchers {

  def assertFasterThan[A](thresholdMs: Long)(thunk: => A): A = {
    val start = System.nanoTime()
    val ret = thunk
    val elapsedMs = (System.nanoTime() - start) / (1000 * 1000)
    if (elapsedMs > thresholdMs)
      fail(s"Block took $elapsedMs ms to execute, which is more than the threshold of $thresholdMs ms.")
    ret
  }

  val ioWaitMs = 10

  /**
   * Not to small a value, because TLS sockets must do computations that take time, even in they do not block.
   */
  val nonBlockingThresholdMs = 750 * 5

}