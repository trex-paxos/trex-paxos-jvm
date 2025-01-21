package com.github.trex_paxos.paxe;

import org.junit.jupiter.api.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class PaxeCryptoTest {
  private static final Logger LOGGER = Logger.getLogger(PaxeCryptoTest.class.getName());
  static final int MAX_UDP_SIZE = 65507;
  static final int REUSED_BUFFER_COUNT = 4;
  static final int GCM_NONCE_LENGTH = 12;
  private final ByteBuffer[] recycledBuffers = new ByteBuffer[REUSED_BUFFER_COUNT];
  private final byte[] sessionKey = new byte[32];
  private final SecureRandom random = new SecureRandom();

  @BeforeAll
  static void setupLogging() {
    final var logLevel = System.getProperty("java.util.logging.ConsoleHandler.level", "WARNING");
    final Level level = Level.parse(logLevel);

    Logger[] loggers = {
        Logger.getLogger(PaxeCrypto.class.getName()),
        LOGGER
    };

    for (Logger logger : loggers) {
      logger.setLevel(level);
      ConsoleHandler handler = new ConsoleHandler();
      handler.setLevel(level);
      logger.addHandler(handler);
      logger.setUseParentHandlers(false);
    }
  }

  @BeforeEach
  void setup() {
    // Initialize test key
    for (int i = 0; i < sessionKey.length; i++) {
      sessionKey[i] = (byte)i;
    }

    // Initialize buffers with random content
    for (int i = 0; i < REUSED_BUFFER_COUNT; i++) {
      recycledBuffers[i] = ByteBuffer.allocateDirect(MAX_UDP_SIZE);
      byte[] junk = new byte[MAX_UDP_SIZE];
      random.nextBytes(junk);
      recycledBuffers[i].put(junk);
    }
  }

  @Test
  void testStandardEncryption() {
    byte[] payload = new byte[32];
    random.nextBytes(payload);

    ByteBuffer encrypt = getBuffer();
    PaxeCrypto.encryptStandard(encrypt, payload, sessionKey);
    encrypt.flip();

    ByteBuffer decrypt = getBuffer();
    byte[] decrypted = PaxeCrypto.decrypt(decrypt, encrypt, sessionKey);
    assertArrayEquals(payload, decrypted, "Decrypted payload should match original");

    encrypt.rewind();
    assertEquals(0, encrypt.get() & 0x01, "Standard encryption flag should be 0");
  }

  @Test
  void testDekEncryption() {
    byte[] payload = new byte[1024];
    random.nextBytes(payload);

    ByteBuffer encrypt = getBuffer();
    PaxeCrypto.dekEncrypt(encrypt, payload, sessionKey);
    encrypt.flip();

    // Verify DEK flag
    assertEquals(1, encrypt.get() & 0x01, "DEK encryption flag should be 1");
    encrypt.rewind();

    ByteBuffer decrypt = getBuffer();
    byte[] decrypted = PaxeCrypto.decrypt(decrypt, encrypt, sessionKey);
    assertArrayEquals(payload, decrypted, "DEK decrypted payload should match original");
  }

  @Test
  void testDecryptionFailsWithWrongKey() {
    byte[] payload = new byte[32];
    random.nextBytes(payload);
    byte[] wrongKey = new byte[32];
    random.nextBytes(wrongKey);

    ByteBuffer encrypt = getBuffer();
    PaxeCrypto.encryptStandard(encrypt, payload, sessionKey);
    encrypt.flip();

    ByteBuffer decrypt = getBuffer();
    assertThrows(SecurityException.class, () ->
            PaxeCrypto.decrypt(decrypt, encrypt, wrongKey),
        "Decryption with wrong key should fail with SecurityException");
  }

  @Test
  void testDecryptionFailsWithCorruptedData() {
    byte[] payload = new byte[32];
    random.nextBytes(payload);

    ByteBuffer encrypt = getBuffer();
    PaxeCrypto.encryptStandard(encrypt, payload, sessionKey);

    // Get encrypted data as bytes
    encrypt.flip();
    byte[] corruptMe = new byte[encrypt.remaining()];
    encrypt.get(corruptMe);

    // Corrupt a byte in the encrypted data (not the header)
    corruptMe[GCM_NONCE_LENGTH + 2] ^= 1;

    // Put corrupted data back
    ByteBuffer corrupted = getBuffer();
    corrupted.put(corruptMe).flip();

    ByteBuffer decrypt = getBuffer();
    assertThrows(SecurityException.class, () ->
            PaxeCrypto.decrypt(decrypt, corrupted, sessionKey),
        "Decryption of corrupted data should fail with SecurityException");
  }

  @Test
  void testDecryptionFailsWithTruncatedMessage() {
    byte[] payload = new byte[32];
    random.nextBytes(payload);

    ByteBuffer encrypt = getBuffer();
    PaxeCrypto.encryptStandard(encrypt, payload, sessionKey);
    encrypt.flip();

    // Create truncated buffer
    ByteBuffer truncated = getBuffer();
    byte[] data = new byte[encrypt.remaining() - GCM_NONCE_LENGTH]; // Remove nonce
    encrypt.get(data);
    truncated.put(data).flip();

    ByteBuffer decrypt = getBuffer();
    assertThrows(IllegalArgumentException.class, () ->
            PaxeCrypto.decrypt(decrypt, truncated, sessionKey),
        "Decryption of truncated message should fail with IllegalArgumentException");
  }

  @Test
  void testSmallPayloadUsesDirect() {
    byte[] payload = new byte[PaxeCrypto.DEK_THRESHOLD - 1];
    random.nextBytes(payload);

    ByteBuffer encrypt = getBuffer();
    PaxeCrypto.dekEncrypt(encrypt, payload, sessionKey);
    encrypt.flip();

    assertEquals(0, encrypt.get() & 0x01, "Small payload should use direct encryption");
  }

  private ByteBuffer getBuffer() {
    ByteBuffer buffer = recycledBuffers[random.nextInt(REUSED_BUFFER_COUNT)];
    buffer.clear();
    return buffer;
  }
}
