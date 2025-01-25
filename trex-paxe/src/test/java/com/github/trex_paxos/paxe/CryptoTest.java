package com.github.trex_paxos.paxe;

import org.junit.jupiter.api.*;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import static com.github.trex_paxos.paxe.PaxeLogger.LOGGER;
import static com.github.trex_paxos.paxe.PaxeProtocol.*;
import static org.junit.jupiter.api.Assertions.*;

class CryptoTest {
  static final int REUSED_BUFFER_COUNT = 4;
  private final ByteBuffer[] recycledBuffers = new ByteBuffer[REUSED_BUFFER_COUNT];
  private final byte[] sessionKey = new byte[32];
  private final SecureRandom random = new SecureRandom();

  @BeforeAll
  static void setupLogging() {
    final var logLevel = System.getProperty("java.util.logging.ConsoleHandler.level", "WARNING");
    final Level level = Level.parse(logLevel);
    ConsoleHandler handler = new ConsoleHandler();
    handler.setLevel(level);
    LOGGER.addHandler(handler);
    LOGGER.setLevel(level);
    LOGGER.setUseParentHandlers(false);
  }

  @BeforeEach
  void setup() {
    LOGGER.finest(() -> "Setting up test");

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
      int finalI = i;
      LOGGER.finest(() -> String.format("Initialized buffer[%d] position=%d limit=%d",
          finalI, recycledBuffers[finalI].position(), recycledBuffers[finalI].limit()));
    }
  }

  @Test
  void testStandardEncryption() {
    byte[] payload = new byte[32];
    random.nextBytes(payload);

    ByteBuffer encrypt = getBuffer();
    ByteBuffer inputBuffer = BufferUtils.wrapBytes(payload, true);

    LOGGER.finest(() -> String.format("Original buffer: %s",
        dumpBuffer(encrypt, 0, 16)));

    Crypto.encryptStandard(encrypt, inputBuffer, sessionKey);
    encrypt.flip();  // Prepare for reading

    LOGGER.finest(() -> String.format("Flipped encrypted buffer: pos=%d limit=%d remaining=%d",
        encrypt.position(), encrypt.limit(), encrypt.remaining()));
    LOGGER.finest(() -> String.format("Buffer content: %s",
        dumpBuffer(encrypt, 0, Math.min(16, encrypt.remaining()))));

    byte[] decrypted = Crypto.decrypt(encrypt, sessionKey);
    assertArrayEquals(payload, decrypted, "Decrypted payload should match original");
  }

  private static final int DEK_HEADER_SIZE = 1 + GCM_NONCE_LENGTH;  // flags + nonce



  // Add dumpBuffer helper identical to the one in Crypto
  private static String dumpBuffer(ByteBuffer buffer, int start, int len) {
    StringBuilder sb = new StringBuilder();
    int pos = buffer.position();
    buffer.position(start);
    for(int i = 0; i < len && buffer.hasRemaining(); i++) {
      sb.append(String.format("%02x ", buffer.get()));
    }
    buffer.position(pos);
    return sb.toString();
  }


  @Test
  void testDekEncryption() {
    byte[] payload = new byte[1024];
    random.nextBytes(payload);

    ByteBuffer encrypt = getBuffer();
    ByteBuffer inputBuffer = BufferUtils.wrapBytes(payload, true);

    LOGGER.finest(() -> String.format("DEK buffer start: pos=%d limit=%d",
        encrypt.position(), encrypt.limit()));

    Crypto.encryptDek(encrypt, inputBuffer, sessionKey);
    encrypt.flip();

    LOGGER.finest(() -> String.format("DEK encrypted: pos=%d limit=%d remaining=%d",
        encrypt.position(), encrypt.limit(), encrypt.remaining()));
    LOGGER.finest(() -> String.format("DEK header: %s",
        dumpBuffer(encrypt, 0, DEK_HEADER_SIZE)));

    byte[] decrypted = Crypto.decrypt(encrypt, sessionKey);
    assertArrayEquals(payload, decrypted, "DEK decrypted payload should match");
  }

  @Test
  void testDecryptionFailsWithWrongKey() {
    LOGGER.finest(() -> "Starting testDecryptionFailsWithWrongKey");
    byte[] payload = new byte[32];
    random.nextBytes(payload);
    byte[] wrongKey = new byte[32];
    random.nextBytes(wrongKey);

    ByteBuffer encrypt = getBuffer();
    ByteBuffer inputBuffer = BufferUtils.wrapBytes(payload, true);
    Crypto.encryptStandard(encrypt, inputBuffer, sessionKey);
    encrypt.flip();
    LOGGER.finest(() -> String.format("testDecryptionFailsWithWrongKey: after encrypt position=%d remaining=%d",
        encrypt.position(), encrypt.remaining()));

    assertThrows(SecurityException.class, () ->
            Crypto.decrypt(encrypt, wrongKey),
        "Decryption with wrong key should fail with SecurityException");
  }

  @Test
  void testDecryptionFailsWithCorruptedData() {
    LOGGER.finest(() -> "Starting testDecryptionFailsWithCorruptedData");
    byte[] payload = new byte[32];
    random.nextBytes(payload);

    ByteBuffer encrypt = getBuffer();
    ByteBuffer inputBuffer = BufferUtils.wrapBytes(payload, true);
    Crypto.encryptStandard(encrypt, inputBuffer, sessionKey);

    // Get encrypted data as bytes
    encrypt.flip();
    byte[] corruptMe = new byte[encrypt.remaining()];
    encrypt.get(corruptMe);
    LOGGER.finest(() -> String.format("testDecryptionFailsWithCorruptedData: corruptMe.length=%d", corruptMe.length));

    // Corrupt a byte in the encrypted data (not the header)
    corruptMe[GCM_NONCE_LENGTH + 2] ^= 1;

    // Put corrupted data back
    ByteBuffer corrupted = getBuffer();
    corrupted.put(corruptMe).flip();
    LOGGER.finest(() -> String.format("testDecryptionFailsWithCorruptedData: corrupted buffer position=%d remaining=%d",
        corrupted.position(), corrupted.remaining()));

    assertThrows(SecurityException.class, () ->
            Crypto.decrypt(corrupted, sessionKey),
        "Decryption of corrupted data should fail with SecurityException");
  }

  @Test
  void testDecryptionFailsWithTruncatedMessage() {
    LOGGER.finest(() -> "Starting testDecryptionFailsWithTruncatedMessage");
    byte[] payload = new byte[32];
    random.nextBytes(payload);

    ByteBuffer encrypt = getBuffer();
    ByteBuffer inputBuffer = BufferUtils.wrapBytes(payload, true);
    Crypto.encryptStandard(encrypt, inputBuffer, sessionKey);
    encrypt.flip();
    LOGGER.finest(() -> String.format("testDecryptionFailsWithTruncatedMessage: encrypted length=%d",
        encrypt.remaining()));

    // Create truncated buffer
    byte[] data = new byte[encrypt.remaining() - GCM_NONCE_LENGTH]; // Remove nonce
    encrypt.get(data);
    LOGGER.finest(() -> String.format("testDecryptionFailsWithTruncatedMessage: truncated data length=%d",
        data.length));

    ByteBuffer truncated = BufferUtils.wrapBytes(data, true);
    assertThrows(SecurityException.class, () ->
            Crypto.decrypt(truncated, sessionKey),
        "Decryption of truncated message should fail with SecurityException");
  }

  @Test
  void testSmallPayloadUsesDirect() {
    LOGGER.finest(() -> "Starting testSmallPayloadUsesDirect");
    byte[] payload = new byte[DEK_THRESHOLD - 1];
    random.nextBytes(payload);

    ByteBuffer encrypt = getBuffer();
    ByteBuffer inputBuffer = BufferUtils.wrapBytes(payload, true);
    Crypto.encryptDek(encrypt, inputBuffer, sessionKey);
    encrypt.flip();
    LOGGER.finest(() -> String.format("testSmallPayloadUsesDirect: encrypted length=%d",
        encrypt.remaining()));

    assertEquals(0, encrypt.get() & FLAG_DEK, "Small payload should use direct encryption");
  }

  private ByteBuffer getBuffer() {
    ByteBuffer buffer = recycledBuffers[random.nextInt(REUSED_BUFFER_COUNT)];
    buffer.clear();
    LOGGER.finest(() -> String.format("getBuffer: position=%d limit=%d capacity=%d",
        buffer.position(), buffer.limit(), buffer.capacity()));
    return buffer;
  }
}
