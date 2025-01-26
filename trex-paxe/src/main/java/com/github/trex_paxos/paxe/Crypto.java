package com.github.trex_paxos.paxe;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import static com.github.trex_paxos.paxe.PaxeProtocol.*;

/// Cryptographic operations for the Paxe protocol with zero-copy buffer handling
public final class Crypto {

  private static final ThreadLocal<SecureRandom> RANDOM = ThreadLocal.withInitial(SecureRandom::new);
  private static final ThreadLocal<Cipher> CIPHER = ThreadLocal.withInitial(() -> {
    try {
      return Cipher.getInstance("AES/GCM/NoPadding");
    } catch (GeneralSecurityException e) {
      throw new RuntimeException("Required crypto algorithm unavailable", e);
    }
  });

  // Add dumpBuffer helper identical to the one in Crypto
  static String dumpBuffer(ByteBuffer buffer, int start, int len) {
    StringBuilder sb = new StringBuilder();
    int pos = buffer.position();
    buffer.position(start);
    for(int i = 0; i < len && buffer.hasRemaining(); i++) {
      sb.append(String.format("%02x ", buffer.get()));
    }
    buffer.position(pos);
    return sb.toString();
  }

  /// Legacy API compatibility
  public static byte[] encrypt(byte[] payload, byte[] sessionKey) {
    ByteBuffer buffer = ByteBuffer.allocateDirect(payload.length);
    buffer.put(payload).flip();
    ByteBuffer output = ByteBuffer.allocateDirect(
        FLAGS_OFFSET + 1 + GCM_NONCE_LENGTH + payload.length + GCM_TAG_LENGTH);

    encryptStandard(output, buffer, sessionKey);
    output.flip();

    byte[] result = new byte[output.remaining()];
    output.get(result);
    return result;
  }

  /// Legacy API compatibility
  public static byte[] decrypt(byte[] encrypted, byte[] sessionKey) {
    ByteBuffer buffer = ByteBuffer.allocateDirect(encrypted.length);
    buffer.put(encrypted).flip();
    return decrypt(buffer, sessionKey);
  }

  public static void encryptStandard(ByteBuffer output, ByteBuffer payload, byte[] sessionKey) {
    try {
      output.position(FLAGS_OFFSET);
      output.put(FLAG_MAGIC_1);

      byte[] nonce = new byte[GCM_NONCE_LENGTH];
      RANDOM.get().nextBytes(nonce);
      output.put(nonce);

      Cipher cipher = CIPHER.get();
      cipher.init(Cipher.ENCRYPT_MODE,
          new SecretKeySpec(sessionKey, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));

      byte[] inputBytes = new byte[payload.remaining()];
      payload.get(inputBytes);
      output.put(cipher.doFinal(inputBytes));
    } catch (GeneralSecurityException e) {
      throw new SecurityException("Encryption failed", e);
    }
  }


  public static byte[] decrypt(ByteBuffer input, byte[] sessionKey) {
    if (!validateStructure(input)) {
      throw new SecurityException("Invalid message flags");
    }

    byte flags = input.get(FLAGS_OFFSET);
    return (flags & FLAG_DEK) == 0 ?
        decryptStandard(input, sessionKey) :
        decryptDek(input, sessionKey);
  }

  private static byte[] decryptStandard(ByteBuffer input, byte[] sessionKey) {
    try {
      // grab the nonce
      int nonceOffset = FLAGS_OFFSET + 1;
      byte[] nonce = new byte[GCM_NONCE_LENGTH];
      input.position(nonceOffset);
      input.get(nonce);

      // grab the encrypted payload
      int payloadOffset = nonceOffset + GCM_NONCE_LENGTH;
      input.position(payloadOffset);
      byte[] encrypted = new byte[input.remaining()];
      input.get(encrypted);

      // decrypt the payload using the session key and nonce
      Cipher cipher = CIPHER.get();
      cipher.init(Cipher.DECRYPT_MODE,
          new SecretKeySpec(sessionKey, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));

      return cipher.doFinal(encrypted);
    } catch (GeneralSecurityException e) {
      throw new SecurityException("Decryption failed", e);
    }
  }

  public record DekPayload(byte[] dekKey, byte[] dekNonce, byte[] dekEncrypted) {
    public DekPayload {
      if (dekKey.length != DEK_KEY_SIZE) {
        throw new IllegalArgumentException("Invalid DEK key length");
      }
      if (dekNonce.length != GCM_NONCE_LENGTH) {
        throw new IllegalArgumentException("Invalid DEK nonce length");
      }
    }
  }


  public static DekPayload dekInner(byte[] payload) throws GeneralSecurityException {
    byte[] dekKey = new byte[DEK_KEY_SIZE];
    RANDOM.get().nextBytes(dekKey);

    byte[] dekNonce = new byte[GCM_NONCE_LENGTH];
    RANDOM.get().nextBytes(dekNonce);

    Cipher dekCipher = CIPHER.get();
    dekCipher.init(Cipher.ENCRYPT_MODE,
        new SecretKeySpec(dekKey, "AES"),
        new GCMParameterSpec(GCM_TAG_LENGTH_BITS, dekNonce));

    byte[] dekEncrypted = dekCipher.doFinal(payload);
    LOGGER.finest(() -> String.format("DEK encrypted: first 8 bytes=%s...last 8 bytes=%s tag=%s",
        dumpBuffer(ByteBuffer.wrap(dekEncrypted), 0, 8),
        dumpBuffer(ByteBuffer.wrap(dekEncrypted), dekEncrypted.length - GCM_TAG_LENGTH - 8, 8),
        dumpBuffer(ByteBuffer.wrap(dekEncrypted), dekEncrypted.length - GCM_TAG_LENGTH, GCM_TAG_LENGTH)));

    return new DekPayload(dekKey, dekNonce, dekEncrypted);
  }

  /// Encrypts a Data Encryption Key (DEK) payload using session encryption
  /// Creates a buffer with:
  /// - [byte] flags: FLAG_DEK | FLAG_MAGIC_1
  /// - [12 bytes] session nonce
  /// - [32 bytes] session-encrypted DEK key
  /// - [12 bytes] DEK nonce (not encrypted)
  /// - [2 bytes] encrypted payload length
  /// - bytes DEK-encrypted payload
  /// - [16 bytes] GCM tag
  ///
  /// @param output Buffer to write the encrypted message to
  /// @param payload The DEK payload containing key, nonce and encrypted data
  /// @param sessionKey The session key to encrypt the DEK key with
  /// @throws SecurityException if encryption fails
  public static void encryptDek(ByteBuffer output, DekPayload payload, byte[] sessionKey) {
    try {
      // Skip zeroing the header - let protocol handle it
      output.position(FLAGS_OFFSET);

      byte flags = (byte)(FLAG_DEK | FLAG_MAGIC_1);
      LOGGER.finest(() -> String.format("Writing flags=%02x at position=%d", flags, output.position()));
      output.put(flags);

      byte[] sessionNonce = new byte[GCM_NONCE_LENGTH];
      RANDOM.get().nextBytes(sessionNonce);
      LOGGER.finest(() -> String.format("Writing sessionNonce at position=%d", output.position()));
      output.put(sessionNonce);

      Cipher sessionCipher = CIPHER.get();
      sessionCipher.init(Cipher.ENCRYPT_MODE,
          new SecretKeySpec(sessionKey, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH_BITS, sessionNonce));

      LOGGER.finest(() -> String.format("Writing encrypted DEK key at position=%d", output.position()));
      final var encryptedKeyPos = output.position();
      output.put(sessionCipher.doFinal(payload.dekKey));
      LOGGER.finest(() -> String.format("Encrypted DEK key: %s", dumpBuffer(output, encryptedKeyPos, DEK_KEY_SIZE + GCM_TAG_LENGTH)));


      LOGGER.finest(() -> String.format("Writing dekNonce at position=%d", output.position()));
      final var noncePos = output.position();
      output.put(payload.dekNonce);
      LOGGER.finest(() -> String.format("DEK nonce: %s", dumpBuffer(output, noncePos, GCM_NONCE_LENGTH)));


      LOGGER.finest(() -> String.format("Writing payload length=%d at position=%d", payload.dekEncrypted.length, output.position()));
      output.putShort((short)payload.dekEncrypted.length);
      output.put(payload.dekEncrypted);

    } catch (GeneralSecurityException e) {
      throw new SecurityException("DEK encryption failed", e);
    }
  }

  /// Decrypts a DEK-encrypted message from a buffer:
  /// 1. Reads session nonce and decrypts DEK key using session key
  /// 2. Reads DEK nonce and payload length
  /// 3. Decrypts payload using DEK key and nonce
  ///
  /// @param input Buffer containing the encrypted message
  /// @param sessionKey Key for decrypting the DEK key
  /// @return Decrypted payload bytes
  /// @throws SecurityException if decryption fails
  static byte[] decryptDek(ByteBuffer input, byte[] sessionKey) {
    try {
      // grab the nonce
      int nonceOffset = FLAGS_OFFSET + 1;
      byte[] sessionNonce = new byte[GCM_NONCE_LENGTH];
      input.position(nonceOffset);
      LOGGER.finest(() -> String.format("Reading session nonce - Buffer state: position=%d limit=%d", input.position(), input.limit()));
      input.get(sessionNonce);
      LOGGER.finest(() -> String.format("Read session nonce: %s", dumpBytes(sessionNonce)));

      // grab the encrypted DEK key
      int dekKeyOffset = nonceOffset + GCM_NONCE_LENGTH;
      input.position(dekKeyOffset);
      byte[] encryptedDekKey = new byte[DEK_KEY_SIZE + GCM_TAG_LENGTH];
      LOGGER.finest(() -> String.format("Reading encrypted dek key - Buffer state: position=%d limit=%d", input.position(), input.limit()));
      input.get(encryptedDekKey);
      LOGGER.finest(() -> String.format("Read encrypted DEK key: %s", dumpBytes(encryptedDekKey)));

      // Decrypt the DEK key using session key
      Cipher sessionCipher = CIPHER.get();
      sessionCipher.init(Cipher.DECRYPT_MODE,
          new SecretKeySpec(sessionKey, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH_BITS, sessionNonce));
      byte[] dekKey = sessionCipher.doFinal(encryptedDekKey);

      // Read DEK nonce
      byte[] dekNonce = new byte[GCM_NONCE_LENGTH];
      LOGGER.finest(() -> String.format("reading dek nonce - Buffer state: position=%d limit=%d", input.position(), input.limit()));
      input.get(dekNonce);
      LOGGER.finest(() -> String.format("Read DEK nonce: %s", dumpBytes(dekNonce)));

      LOGGER.finest(() -> String.format("reading short for payload length - Buffer state: position=%d limit=%d", input.position(), input.limit()));

      // read the inner encrypted payload length
      short payloadLength = input.getShort();

      LOGGER.finest(() -> String.format("Read payloadLength: %d", payloadLength));

      // Read the encrypted payload
      byte[] encryptedPayload = new byte[payloadLength];
      LOGGER.finest(() -> String.format("reading payload and tag - Buffer state: position=%d limit=%d", input.position(), input.limit()));

      input.get(encryptedPayload);

      LOGGER.finest(() -> String.format("Reading encrypted payload: first 8 bytes=%s...last 8 bytes=%s tag=%s",
          dumpBuffer(ByteBuffer.wrap(encryptedPayload), 0, 8),
          dumpBuffer(ByteBuffer.wrap(encryptedPayload), encryptedPayload.length - GCM_TAG_LENGTH - 8, 8),
          dumpBuffer(ByteBuffer.wrap(encryptedPayload), encryptedPayload.length - GCM_TAG_LENGTH, GCM_TAG_LENGTH)));

      Cipher dekCipher = CIPHER.get();
      dekCipher.init(Cipher.DECRYPT_MODE,
          new SecretKeySpec(dekKey, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH_BITS, dekNonce));

      return dekCipher.doFinal(encryptedPayload);

    } catch (GeneralSecurityException e) {
      throw new SecurityException("DEK decryption failed", e);
    }
  }

  private static String dumpBytes(byte[] sessionNonce) {
    StringBuilder sb = new StringBuilder();
    for (byte b : sessionNonce) {
      sb.append(String.format("%02x ", b));
    }
    return sb.toString();
  }
}


