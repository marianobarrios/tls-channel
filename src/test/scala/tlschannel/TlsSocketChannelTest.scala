package tlschannel

import org.scalatest.FunSuite
import org.scalatest.Matchers

class TlsSocketChannelTest extends IntegrationTest {

  import SocketWrappers._

  /*
   * Although the objective of this class is to test the TlsSocketChannel, tests for all the combinations of plain/tls
   * and nio/old_io are present. This is in order to tests the correctness of the test itself.
   */
  
  /*
   * PLAIN SOCKET TESTS
   */

  // PLAIN - OLD IO -> OLD IO

  test("plain: old-io -> old-io (simplex)") {
    val ((clientWriter, _), (_, serverReader)) = plain_Old_Old()
    simplexStream("plain", clientWriter, serverReader)
  }
  
  test("plain: old-io -> old-io (half duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Old_Old()
    halfDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: old-io -> old-io (full duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Old_Old()
    fullDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: old-io -> old-io (closing)") {
    val ((clientWriter, _), (_, serverReader)) = plain_Old_Old()
    closingStream("plain", clientWriter, serverReader)
  }

  // PLAIN - NIO -> NIO

  test("plain: nio -> nio (simplex)") {
    val ((clientWriter, _), (_, serverReader)) = plain_Nio_Nio(blockingClient = true, blockingServer = true)
    simplexStream("plain", clientWriter, serverReader)
  }

  test("plain: nio (nb) -> nio (simplex)") {
    val ((clientWriter, _), (_, serverReader)) = plain_Nio_Nio(blockingClient = false, blockingServer = true)
    simplexStream("plain", clientWriter, serverReader)
  }

  test("plain: nio -> nio (nb) (simplex)") {
    val ((clientWriter, _), (_, serverReader)) = plain_Nio_Nio(blockingClient = true, blockingServer = false)
    simplexStream("plain", clientWriter, serverReader)
  }

  test("plain: nio (nb) -> nio (nb) (simplex)") {
    val ((clientWriter, _), (_, serverReader)) = plain_Nio_Nio(blockingClient = false, blockingServer = false)
    simplexStream("plain", clientWriter, serverReader)
  }

  test("plain: nio -> nio (half duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Nio_Nio(blockingClient = true, blockingServer = true)
    halfDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: nio (nb) -> nio (half duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Nio_Nio(blockingClient = false, blockingServer = true)
    halfDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: nio -> nio (nb) (half duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Nio_Nio(blockingClient = true, blockingServer = false)
    halfDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: nio (nb) -> nio (nb) (half duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Nio_Nio(blockingClient = false, blockingServer = false)
    halfDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: nio -> nio (full duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Nio_Nio(blockingClient = true, blockingServer = true)
    fullDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: nio (nb) -> nio (full duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Nio_Nio(blockingClient = false, blockingServer = true)
    fullDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: nio -> nio (nb) (full duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Nio_Nio(blockingClient = true, blockingServer = false)
    fullDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: nio (nb) -> nio (nb) (full duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Nio_Nio(blockingClient = false, blockingServer = false)
    fullDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: nio -> nio (closing)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Nio_Nio(blockingClient = true, blockingServer = true)
    closingStream("plain", clientWriter, serverReader)
  }

  test("plain: nio (nb) -> nio (closing)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Nio_Nio(blockingClient = false, blockingServer = true)
    closingStream("plain", clientWriter, serverReader)
  }

  // PLAIN - OLD IO -> NIO

  test("plain: old-io -> nio (simplex)") {
    val ((clientWriter, _), (_, serverReader)) = plain_Old_Nio(blockingServer = true)
    simplexStream("plain", clientWriter, serverReader)
  }

  test("plain: old-io -> nio (nb) (simplex)") {
    val ((clientWriter, _), (_, serverReader)) = plain_Old_Nio(blockingServer = false)
    simplexStream("plain", clientWriter, serverReader)
  }

  test("plain: old-io -> nio (half duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Old_Nio(blockingServer = true)
    halfDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: old-io -> nio (nb) (half duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Old_Nio(blockingServer = false)
    halfDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: old-io -> nio (full duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Old_Nio(blockingServer = true)
    fullDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: old-io -> nio (nb) (full duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Old_Nio(blockingServer = false)
    fullDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }
  
  test("plain: old-io -> nio (closing)") {
    val ((clientWriter, _), (_, serverReader)) = plain_Old_Nio(blockingServer = true)
    closingStream("plain", clientWriter, serverReader)
  }

  // PLAIN - NIO -> OLD IO
  
  test("plain: nio -> old-io (simplex)") {
    val ((clientWriter, _), (_, serverReader)) = plain_Nio_Old(blockingClient = true)
    simplexStream("plain", clientWriter, serverReader)
  }

  test("plain: nio (nb) -> old-io (simplex)") {
    val ((clientWriter, _), (_, serverReader)) = plain_Nio_Old(blockingClient = false)
    simplexStream("plain", clientWriter, serverReader)
  }

  test("plain: nio -> old-io (half duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Nio_Old(blockingClient = true)
    halfDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: nio (nb) -> old-io (half duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Nio_Old(blockingClient = false)
    halfDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: nio -> old-io (full duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Nio_Old(blockingClient = true)
    fullDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: nio (nb) -> old-io (full duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Nio_Old(blockingClient = false)
    fullDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: nio -> old-io (closing)") {
    val ((clientWriter, _), (_, serverReader)) = plain_Nio_Old(blockingClient = true)
    closingStream("plain", clientWriter, serverReader)
  }

  test("plain: nio (nb) -> old-io (closing)") {
    val ((clientWriter, _), (_, serverReader)) = plain_Nio_Old(blockingClient = false)
    closingStream("plain", clientWriter, serverReader)
  }

  /*
   * TLS TESTS
   */

  // TLS - OLD IO -> OLD IO    

  test("tls: old-io -> old-io (simplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = tls_Old_Old(cipher)
        simplexStream(cipher, clientWriter, serverReader)
      }
    }
  }

  test("tls: old-io -> old-io (half duplex)") {
    for ((cipher, idx) <- ciphers.zipWithIndex) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = tls_Old_Old(cipher)
        halfDuplexStream(idx, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: old-io -> old-io (full duplex)") {
    for ((cipher, idx) <- ciphers.zipWithIndex) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = tls_Old_Old(cipher)
        fullDuplexStream(idx, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: old-io -> old-io (closing)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = tls_Old_Old(cipher)
        closingStream(cipher, clientWriter, serverReader)
      }
    }
  }

  // TLS - NIO -> OLD IO    

  test("tls: nio -> old-io (simplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = tls_Nio_Old(cipher, blockingClient = true)
        simplexStream(cipher, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio (nb) -> old-io (simplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = tls_Nio_Old(cipher, blockingClient = false)
        simplexStream(cipher, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio -> old-io (half duplex)") {
    for ((cipher, idx) <- ciphers.zipWithIndex) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = tls_Nio_Old(cipher, blockingClient = true)
        halfDuplexStream(idx, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio (nb) -> old-io (alf duplex)") {
    for ((cipher, idx) <- ciphers.zipWithIndex) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = tls_Nio_Old(cipher, blockingClient = false)
        halfDuplexStream(idx, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio -> old-io (full duplex)") {
    for ((cipher, idx) <- ciphers.zipWithIndex) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = tls_Nio_Old(cipher, blockingClient = true)
        fullDuplexStream(idx, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio (nb) -> old-io (full duplex)") {
    for ((cipher, idx) <- ciphers.zipWithIndex) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = tls_Nio_Old(cipher, blockingClient = false)
        fullDuplexStream(idx, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio -> old-io (closing)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = tls_Nio_Old(cipher, blockingClient = true)
        closingStream(cipher, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio (nb) -> old-io (closing)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = tls_Nio_Old(cipher, blockingClient = false)
        closingStream(cipher, clientWriter, serverReader)
      }
    }
  }

  // TLS - OLD IO -> NIO    

  test("tls: old-io -> nio (simplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = tls_Old_Nio(cipher, blockingServer = true)
        simplexStream(cipher, clientWriter, serverReader)
      }
    }
  }

  test("tls: old-io -> nio (nb) (simplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = tls_Old_Nio(cipher, blockingServer = false)
        simplexStream(cipher, clientWriter, serverReader)
      }
    }
  }

  test("tls: old-io -> nio (half duplex)") {
    for ((cipher, idx) <- ciphers.zipWithIndex) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = tls_Old_Nio(cipher, blockingServer = true)
        halfDuplexStream(idx, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: old-io -> nio (nb) (half duplex)") {
    for ((cipher, idx) <- ciphers.zipWithIndex) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = tls_Old_Nio(cipher, blockingServer = false)
        halfDuplexStream(idx, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: old-io -> nio (full duplex)") {
    for ((cipher, idx) <- ciphers.zipWithIndex) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = tls_Old_Nio(cipher, blockingServer = true)
        fullDuplexStream(idx, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: old-io -> nio (nb) (full duplex)") {
    for ((cipher, idx) <- ciphers.zipWithIndex) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = tls_Old_Nio(cipher, blockingServer = false)
        fullDuplexStream(idx, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: old-io -> nio (closing)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = tls_Old_Nio(cipher, blockingServer = true)
        closingStream(cipher, clientWriter, serverReader)
      }
    }
  }

  // TLS - NIO -> NIO

  test("tls: nio -> nio (simplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = tls_Nio_Nio(cipher, blockingClient = true, blockingServer = true)
        simplexStream(cipher, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio (nb) -> nio (simplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = tls_Nio_Nio(cipher, blockingClient = false, blockingServer = true)
        simplexStream(cipher, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio -> nio (nb) (simplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = tls_Nio_Nio(cipher, blockingClient = true, blockingServer = false)
        simplexStream(cipher, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio (nb) -> nio (nb) (simplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = tls_Nio_Nio(cipher, blockingClient = false, blockingServer = false)
        simplexStream(cipher, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio -> nio (half duplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = 
          tls_Nio_Nio(cipher, blockingClient = true, blockingServer = true)
        halfDuplexStream(0, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio (nb) -> nio (half duplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
       val ((clientWriter, clientReader), (serverWriter, serverReader)) = 
          tls_Nio_Nio(cipher, blockingClient = false, blockingServer = true)
        halfDuplexStream(0, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio -> nio (nb) (half duplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = 
          tls_Nio_Nio(cipher, blockingClient = true, blockingServer = false)
        halfDuplexStream(0, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio (nb) -> nio (nb) (half duplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = 
          tls_Nio_Nio(cipher, blockingClient = false, blockingServer = false)
        halfDuplexStream(0, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio -> nio (full duplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = 
          tls_Nio_Nio(cipher, blockingClient = true, blockingServer = true)
        fullDuplexStream(0, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio (nb) -> nio (full duplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = 
          tls_Nio_Nio(cipher, blockingClient = false, blockingServer = true)
        fullDuplexStream(0, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio -> nio (nb) (full duplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = 
          tls_Nio_Nio(cipher, blockingClient = true, blockingServer = false)
        fullDuplexStream(0, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio (nb) -> nio (nb) (full duplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = 
          tls_Nio_Nio(cipher, blockingClient = false, blockingServer = false)
        fullDuplexStream(0, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio -> nio (closing)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = tls_Nio_Nio(cipher, blockingClient = true, blockingServer = true)
        closingStream(cipher, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio (nb) -> nio (closing)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = tls_Nio_Nio(cipher, blockingClient = false, blockingServer = true)
        closingStream(cipher, clientWriter, serverReader)
      }
    }
  }

}
