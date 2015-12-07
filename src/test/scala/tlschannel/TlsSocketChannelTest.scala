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
    val ((clientWriter, _), (_, serverReader)) = plain_Nio_Nio()
    simplexStream("plain", clientWriter, serverReader)
  }

  test("plain: nio -> nio (half duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Nio_Nio()
    halfDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: nio -> nio (full duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Nio_Nio()
    fullDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: nio -> nio (closing)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Nio_Nio()
    closingStream("plain", clientWriter, serverReader)
  }

  // PLAIN - OLD IO -> NIO

  test("plain: old-io -> nio (simplex)") {
    val ((clientWriter, _), (_, serverReader)) = plain_Old_Nio()
    simplexStream("plain", clientWriter, serverReader)
  }

  test("plain: old-io -> nio (half duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Old_Nio()
    halfDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: old-io -> nio (full duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Old_Nio()
    fullDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: old-io -> nio (closing)") {
    val ((clientWriter, _), (_, serverReader)) = plain_Old_Nio()
    closingStream("plain", clientWriter, serverReader)
  }

  // PLAIN - NIO -> OLD IO
  
  test("plain: nio -> old-io (simplex)") {
    val ((clientWriter, _), (_, serverReader)) = plain_Nio_Old()
    simplexStream("plain", clientWriter, serverReader)
  }
  
  test("plain: nio -> old-io (half duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Nio_Old()
    halfDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: nio -> old-io (full duplex)") {
    val ((clientWriter, clientReader), (serverWriter, serverReader)) = plain_Nio_Old()
    fullDuplexStream(0, "plain", serverWriter, clientReader, clientWriter, serverReader)
  }

  test("plain: nio -> old-io (closing)") {
    val ((clientWriter, _), (_, serverReader)) = plain_Nio_Old()
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
        val ((clientWriter, _), (_, serverReader)) = tls_Nio_Old(cipher)
        simplexStream(cipher, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio -> old-io (half duplex)") {
    for ((cipher, idx) <- ciphers.zipWithIndex) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = tls_Nio_Old(cipher)
        halfDuplexStream(idx, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio -> old-io (full duplex)") {
    for ((cipher, idx) <- ciphers.zipWithIndex) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = tls_Nio_Old(cipher)
        fullDuplexStream(idx, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio -> old-io (closing)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = tls_Nio_Old(cipher)
        closingStream(cipher, clientWriter, serverReader)
      }
    }
  }

  // TLS - OLD IO -> NIO    

  test("tls: old-io -> nio (simplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = tls_Old_Nio(cipher)
        simplexStream(cipher, clientWriter, serverReader)
      }
    }
  }

  test("tls: old-io -> nio (half duplex)") {
    for ((cipher, idx) <- ciphers.zipWithIndex) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = tls_Old_Nio(cipher)
        halfDuplexStream(idx, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: old-io -> nio (full duplex)") {
    for ((cipher, idx) <- ciphers.zipWithIndex) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = tls_Old_Nio(cipher)
        fullDuplexStream(idx, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: old-io -> nio (closing)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = tls_Old_Nio(cipher)
        closingStream(cipher, clientWriter, serverReader)
      }
    }
  }

  // TLS - NIO -> NIO

  test("tls: nio -> nio (simplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = tls_Nio_Nio(cipher)
        simplexStream(cipher, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio -> nio (half duplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = 
          tls_Nio_Nio(cipher)
        halfDuplexStream(0, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio -> nio (full duplex)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, clientReader), (serverWriter, serverReader)) = 
          tls_Nio_Nio(cipher)
        fullDuplexStream(0, cipher, serverWriter, clientReader, clientWriter, serverReader)
      }
    }
  }

  test("tls: nio -> nio (closing)") {
    for (cipher <- ciphers) {
      withClue(cipher + ": ") {
        val ((clientWriter, _), (_, serverReader)) = tls_Nio_Nio(cipher)
        closingStream(cipher, clientWriter, serverReader)
      }
    }
  }

}
