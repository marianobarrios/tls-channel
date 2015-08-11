package tlschannel

import java.util.concurrent.locks.Lock
import java.nio.channels.Channel

object Util {

  def withLock[A](lock: Lock)(thunk: => A): A = {
    lock.lock()
    try {
      thunk
    } finally {
      lock.unlock()
    }
  }
  
  def closeChannel(channel: Channel): Unit = {
    try {
      channel.close()
    } catch {
      case e: Throwable => // pass
    }
  }
}