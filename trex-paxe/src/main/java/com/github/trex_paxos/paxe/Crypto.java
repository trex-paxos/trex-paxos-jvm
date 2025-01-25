package com.github.trex_paxos.paxe;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import static com.github.trex_paxos.paxe.PaxeLogger.LOGGER;
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

  /// Legacy API compatibility
  public static byte[] encrypt(byte[] payload, byte[] sessionKey) {
    ByteBuffer input = BufferUtils.wrapBytes(payload, true);
    ByteBuffer output = ByteBuffer.allocateDirect(
        FLAGS_OFFSET + 1 + GCM_NONCE_LENGTH + payload.length + GCM_TAG_LENGTH);

    encryptStandard(output, input, sessionKey);
    output.flip();

    byte[] result = new byte[output.remaining()];
    output.get(result);
    return result;
  }

  /// Legacy API compatibility
  public static byte[] decrypt(byte[] encrypted, byte[] sessionKey) {
    ByteBuffer input = BufferUtils.wrapBytes(encrypted, true);
    return decrypt(input, sessionKey);
  }

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

  public static void encryptDek(ByteBuffer output, ByteBuffer payload, byte[] sessionKey) {
    LOGGER.finest(() -> String.format("ENCRYPT_DEK START: payload.remaining=%d output.pos=%d",
        payload.remaining(), output.position()));
    LOGGER.finest(() -> String.format("Pre-write buffer: %s",
        dumpBuffer(output, output.position(), 16)));

    if (payload.remaining() <= DEK_THRESHOLD) {
      LOGGER.finest("Small payload, using standard encryption");
      encryptStandard(output, payload, sessionKey);
      return;
    }

    try {
      KeyGenerator keyGen = KeyGenerator.getInstance("AES");
      keyGen.init(128);
      byte[] dekKey = keyGen.generateKey().getEncoded();
      LOGGER.finest(() -> String.format("Generated DEK key: %s",
          dumpBuffer(ByteBuffer.wrap(dekKey), 0, dekKey.length)));

      byte[] sessionNonce = new byte[GCM_NONCE_LENGTH];
      byte[] dekNonce = new byte[GCM_NONCE_LENGTH];
      RANDOM.get().nextBytes(sessionNonce);
      RANDOM.get().nextBytes(dekNonce);
      LOGGER.finest(() -> String.format("Session nonce: %s DEK nonce: %s",
          dumpBuffer(ByteBuffer.wrap(sessionNonce), 0, sessionNonce.length),
          dumpBuffer(ByteBuffer.wrap(dekNonce), 0, dekNonce.length)));

      byte flags = (byte)(FLAG_DEK | FLAG_MAGIC_1);
      LOGGER.finest(() -> String.format("Writing flags=0x%02x at position=%d",
          flags, output.position()));

      output.put(flags);
      LOGGER.finest(() -> String.format("After flags: %s",
          dumpBuffer(output, 0, 16)));

      output.put(sessionNonce);
      LOGGER.finest(() -> String.format("After session nonce: %s",
          dumpBuffer(output, 0, 16)));

      ByteBuffer envelopeBuffer = ByteBuffer.allocate(DEK_SECTION_SIZE);
      PaxeProtocol.writeEnvelope(envelopeBuffer, dekKey, dekNonce, (short)payload.remaining());
      envelopeBuffer.flip();
      LOGGER.finest(() -> String.format("Envelope buffer: %s",
          dumpBuffer(envelopeBuffer, 0, envelopeBuffer.remaining())));

      byte[] envelopeBytes = new byte[envelopeBuffer.remaining()];
      envelopeBuffer.get(envelopeBytes);

      Cipher sessionCipher = CIPHER.get();
      sessionCipher.init(Cipher.ENCRYPT_MODE,
          new SecretKeySpec(sessionKey, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH_BITS, sessionNonce));

      byte[] encryptedEnvelope = sessionCipher.doFinal(envelopeBytes);
      LOGGER.finest(() -> String.format("Encrypted envelope: %s",
          dumpBuffer(ByteBuffer.wrap(encryptedEnvelope), 0, Math.min(16, encryptedEnvelope.length))));
      output.put(encryptedEnvelope);

      Cipher dekCipher = CIPHER.get();
      dekCipher.init(Cipher.ENCRYPT_MODE,
          new SecretKeySpec(dekKey, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH_BITS, dekNonce));

      byte[] payloadBytes = new byte[payload.remaining()];
      payload.get(payloadBytes);
      LOGGER.finest(() -> String.format("Raw payload: %s",
          dumpBuffer(ByteBuffer.wrap(payloadBytes), 0, Math.min(16, payloadBytes.length))));

      byte[] encryptedPayload = dekCipher.doFinal(payloadBytes);
      LOGGER.finest(() -> String.format("Encrypted payload: %s",
          dumpBuffer(ByteBuffer.wrap(encryptedPayload), 0, Math.min(16, encryptedPayload.length))));

      output.put(encryptedPayload);
      LOGGER.finest(() -> String.format("Final buffer: %s",
          dumpBuffer(output, 0, Math.min(32, output.position()))));

      LOGGER.finest(() -> String.format("ENCRYPT_DEK COMPLETE: output.position=%d",
          output.position()));

    } catch (GeneralSecurityException e) {
      LOGGER.warning(() -> "DEK encryption failed: " + e.getMessage());
      throw new SecurityException("DEK encryption failed", e);
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
      int nonceOffset = FLAGS_OFFSET + 1;
      byte[] nonce = new byte[GCM_NONCE_LENGTH];
      input.position(nonceOffset);
      input.get(nonce);

      int payloadOffset = nonceOffset + GCM_NONCE_LENGTH;
      input.position(payloadOffset);
      byte[] encrypted = new byte[input.remaining()];
      input.get(encrypted);

      Cipher cipher = CIPHER.get();
      cipher.init(Cipher.DECRYPT_MODE,
          new SecretKeySpec(sessionKey, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));

      return cipher.doFinal(encrypted);
    } catch (GeneralSecurityException e) {
      throw new SecurityException("Decryption failed", e);
    }
  }

  private static String dumpBytes(ByteBuffer buf, int start, int len) {
    StringBuilder sb = new StringBuilder();
    int pos = buf.position();
    buf.position(start);
    for(int i = 0; i < len && buf.hasRemaining(); i++) {
      sb.append(String.format("%02x ", buf.get()));
    }
    buf.position(pos);
    return sb.toString();
  }


  private static byte[] decryptDek(ByteBuffer input, byte[] sessionKey) {

    try {
      if (input.remaining() < GCM_NONCE_LENGTH + DEK_SECTION_SIZE) {
        throw new SecurityException("Message too short for DEK format");
      }

      byte[] sessionNonce = new byte[GCM_NONCE_LENGTH];
      input.get(sessionNonce);

      byte[] encryptedEnvelope = new byte[DEK_SECTION_SIZE];
      input.get(encryptedEnvelope);

      Cipher sessionCipher = CIPHER.get();
      sessionCipher.init(Cipher.DECRYPT_MODE,
          new SecretKeySpec(sessionKey, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH_BITS, sessionNonce));

      ByteBuffer envelopeBuffer = ByteBuffer.wrap(sessionCipher.doFinal(encryptedEnvelope));
      Envelope envelope = PaxeProtocol.readEnvelope(envelopeBuffer);

      byte[] encryptedPayload = new byte[input.remaining()];
      input.get(encryptedPayload);

      LOGGER.finest(() -> String.format("DEK decrypt: envelope=%d payload=%d",
          DEK_SECTION_SIZE, encryptedPayload.length));

      Cipher dekCipher = CIPHER.get();
      dekCipher.init(Cipher.DECRYPT_MODE,
          new SecretKeySpec(envelope.dekKey(), "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH_BITS, envelope.dekNonce()));

      byte[] result = dekCipher.doFinal(encryptedPayload);

      if (result.length != envelope.payloadLength()) {
        throw new SecurityException("Payload length mismatch");
      }

      return result;

    } catch (GeneralSecurityException e) {
      LOGGER.warning(() -> "DEK decryption failed: " + e.getMessage());
      throw new SecurityException("DEK decryption failed", e);
    }
  }
}
