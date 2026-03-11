package tlschannel.impl;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLProtocolException;
import javax.net.ssl.StandardConstants;

/** Implements basic TLS parsing, just to read the SNI. */
public final class TlsExplorer {

    private TlsExplorer() {}

    /*
     * struct {
     *   uint8 major;
     *   uint8 minor;
     * } ProtocolVersion;
     *
     * enum {
     *   change_cipher_spec(20),
     *   alert(21),
     *   handshake(22),
     *   application_data(23),
     *   (255)
     * } ContentType;
     *
     * struct {
     *   ContentType type;
     *   ProtocolVersion version;
     *   uint16 length;
     *   opaque fragment[TLSPlaintext.length];
     * } TLSPlaintext;
     */
    /** Explores a TLS record in search of the SNI. This method does not consume the buffer. */
    public static Map<Integer, SNIServerName> exploreTlsRecord(ByteBuffer input) throws SSLProtocolException {

        input.mark();
        try {
            byte firstByte = input.get();
            if (firstByte != 22) {
                // 22: handshake record
                throw new SSLProtocolException("Not a handshake record");
            }

            ignore(input, 2); // ignore version

            // Is there enough data for a full record?
            int recordLength = getUnsignedInt16(input);
            if (recordLength > input.remaining()) {
                throw new BufferUnderflowException();
            }

            return exploreHandshake(input, recordLength);
        } finally {
            input.reset();
        }
    }

    /*
     * enum {
     *   hello_request(0),
     *   client_hello(1),
     *   server_hello(2),
     *   certificate(11),
     *   server_key_exchange (12),
     *   certificate_request(13),
     *   server_hello_done(14),
     *   certificate_verify(15),
     *   client_key_exchange(16),
     *   finished(20),
     *   (255)
     * } HandshakeType;
     *
     * struct {
     *   HandshakeType msg_type;
     *   uint24 length;
     *   select (HandshakeType) {
     *     case hello_request: HelloRequest;
     *     case client_hello: ClientHello;
     *     case server_hello: ServerHello;
     *     case certificate: Certificate;
     *     case server_key_exchange: ServerKeyExchange;
     *     case certificate_request: CertificateRequest;
     *     case server_hello_done: ServerHelloDone;
     *     case certificate_verify: CertificateVerify;
     *     case client_key_exchange: ClientKeyExchange;
     *     case finished: Finished;
     *   } body;
     * } Handshake;
     */
    private static Map<Integer, SNIServerName> exploreHandshake(ByteBuffer input, int recordLength)
            throws SSLProtocolException {
        byte handshakeType = input.get();
        if (handshakeType != 0x01) {
            // 0x01: client_hello message
            throw new SSLProtocolException("Not an initial handshake");
        }

        // What is the handshake body length?
        int handshakeLength = getUnsignedInt24(input);

        // Theoretically, a single handshake message might span multiple
        // records, but in practice this does not occur.
        if (handshakeLength > recordLength - 4) {
            // 4: handshake header size
            throw new SSLProtocolException("Handshake message spans multiple records");
        }
        input.limit(handshakeLength + input.position());

        return exploreClientHello(input);
    }

    /*
     * struct {
     *   uint32 gmt_unix_time;
     *   opaque random_bytes[28];
     * } Random;
     *
     * opaque SessionID<0..32>;
     *
     * uint8 CipherSuite[2];
     *
     * enum {
     *   null(0),
     *   (255)
     * } CompressionMethod;
     *
     * struct {
     *   ProtocolVersion client_version;
     *   Random random;
     *   SessionID session_id;
     *   CipherSuite cipher_suites<2..2^16-2>;
     *   CompressionMethod compression_methods<1..2^8-1>;
     *   select (extensions_present) {
     *     case false: struct {};
     *     case true: Extension extensions<0..2^16-1>;
     *   };
     * } ClientHello;
     */
    private static Map<Integer, SNIServerName> exploreClientHello(ByteBuffer input) throws SSLProtocolException {
        ignore(input, 2); // ignore version
        ignore(input, 32); // ignore random; 32: the length of Random
        ignoreByteVector8(input); // ignore session id
        ignoreByteVector16(input); // ignore cipher_suites
        ignoreByteVector8(input); // ignore compression methods
        if (input.hasRemaining()) {
            return exploreExtensions(input);
        } else {
            return new HashMap<>();
        }
    }

    /*
     * struct {
     *   ExtensionType extension_type;
     *   opaque extension_data<0..2^16-1>;
     * } Extension;
     *
     * enum {
     *   server_name(0),
     *   max_fragment_length(1),
     *   client_certificate_url(2),
     *   trusted_ca_keys(3),
     *   truncated_hmac(4),
     *   status_request(5),
     *   (65535)
     * }
     * ExtensionType;
     */
    private static Map<Integer, SNIServerName> exploreExtensions(ByteBuffer input) throws SSLProtocolException {
        int length = getUnsignedInt16(input); // length of extensions
        while (length > 0) {
            int extType = getUnsignedInt16(input); // extension type
            int extLen = getUnsignedInt16(input); // length of extension data
            if (extType == 0x00) {
                // 0x00: type of server name indication
                return exploreSNIExt(input, extLen);
            } else {
                // ignore other extensions
                ignore(input, extLen);
            }
            length -= extLen + 4;
        }
        return new HashMap<>();
    }

    /*
     * struct {
     *   NameType name_type;
     *   select (name_type) {
     *     case host_name: HostName;
     *   } name;
     * } ServerName;
     *
     * enum {
     *   host_name(0),
     *   (255)
     * } NameType;
     *
     * opaque HostName<1..2^16-1>;
     *
     * struct {
     *   ServerName server_name_list<1..2^16-1>
     * } ServerNameList;
     */
    private static Map<Integer, SNIServerName> exploreSNIExt(ByteBuffer input, int extLen) throws SSLProtocolException {
        Map<Integer, SNIServerName> sniMap = new HashMap<>();
        int remains = extLen;
        if (extLen >= 2) {
            // "server_name" extension in ClientHello
            int listLen = getUnsignedInt16(input); // length of server_name_list
            if (listLen == 0 || listLen + 2 != extLen) {
                throw new SSLProtocolException("Invalid server name indication extension");
            }
            remains -= 2; // 2: the length field of server_name_list
            while (remains > 0) {
                int code = getUnsignedInt8(input); // name_type
                int snLen = getUnsignedInt16(input); // length field of server name
                if (snLen > remains) {
                    throw new SSLProtocolException("Not enough data to fill declared vector size");
                }
                byte[] encoded = new byte[snLen];
                input.get(encoded);
                SNIServerName serverName;
                if (code == StandardConstants.SNI_HOST_NAME) {
                    if (encoded.length == 0) {
                        throw new SSLProtocolException("Empty HostName in server name indication");
                    }
                    serverName = new SNIHostName(encoded);
                } else {
                    serverName = new UnknownServerName(code, encoded);
                }
                // check for duplicated server name type
                if (sniMap.put(serverName.getType(), serverName) != null) {
                    throw new SSLProtocolException("Duplicated server name of type " + serverName.getType());
                }
                remains -= encoded.length + 3; // NameType: 1 byte; HostName
                // length: 2 bytes
            }
        } else if (extLen == 0) {
            // "server_name" extension in ServerHello
            throw new SSLProtocolException("Not server name indication extension in client");
        }
        if (remains != 0) {
            throw new SSLProtocolException("Invalid server name indication extension");
        }
        return sniMap;
    }

    /*
     * Java's byte type is signed, so ByteBuffer.get() returns a value in [-128, 127]. When it is
     * widened to int for arithmetic or return, the sign is respected. In bits, the byte 0x80
     * becomes the int 0xFFFFFF80, not 0x00000080. Masking with 0xFF clears those upper bits, giving
     * the correct unsigned value (0..255).
     */

    private static int getUnsignedInt8(ByteBuffer input) {
        return input.get() & 0xFF;
    }

    private static int getUnsignedInt16(ByteBuffer input) {
        return ((input.get() & 0xFF) << 8) | (input.get() & 0xFF);
    }

    private static int getUnsignedInt24(ByteBuffer input) {
        return ((input.get() & 0xFF) << 16) | ((input.get() & 0xFF) << 8) | (input.get() & 0xFF);
    }

    private static void ignoreByteVector8(ByteBuffer input) {
        ignore(input, getUnsignedInt8(input));
    }

    private static void ignoreByteVector16(ByteBuffer input) {
        ignore(input, getUnsignedInt16(input));
    }

    private static void ignore(ByteBuffer input, int length) {
        if (length != 0) {
            if (input.remaining() < length) {
                throw new BufferUnderflowException();
            }
            input.position(input.position() + length);
        }
    }

    // For some reason, SNIServerName is abstract
    private static class UnknownServerName extends SNIServerName {
        UnknownServerName(int code, byte[] encoded) {
            super(code, encoded);
        }
    }
}
