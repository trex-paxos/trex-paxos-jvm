package com.github.trex_paxos.paxe;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import java.util.logging.Logger;

public final class PaxeCrypto {
  private static final Logger LOGGER = Logger.getLogger(PaxeCrypto.class.getName());
  private static final int GCM_NONCE_LENGTH = 12;
  private static final int GCM_AUTH_TAG_LENGTH = 16;
  private static final int GCM_TAG_LENGTH_BITS = 128;
  public static final int DEK_THRESHOLD = 64;
  private static final byte FLAG_DEK = 0x01;
  public static final int DEK_SIZE = 16;
  public static final int DEK_SECTION_SIZE = DEK_SIZE + GCM_NONCE_LENGTH + GCM_AUTH_TAG_LENGTH + 2;

  private static final ThreadLocal<SecureRandom> RANDOM = ThreadLocal.withInitial(SecureRandom::new);
  private static final ThreadLocal<Cipher> CIPHER = ThreadLocal.withInitial(() -> {
    try {
      return Cipher.getInstance("AES/GCM/NoPadding");
    } catch (GeneralSecurityException e) {
      throw new RuntimeException("Required crypto algorithm unavailable", e);
    }
  });

  // Legacy API for PaxeNetwork compatibility
  public static byte[] encrypt(byte[] payload, byte[] sessionKey) {
    ByteBuffer buffer = ByteBuffer.allocate(1 + GCM_NONCE_LENGTH + payload.length + GCM_AUTH_TAG_LENGTH);
    encryptStandard(buffer, payload, sessionKey);
    buffer.flip();
    byte[] result = new byte[buffer.remaining()];
    buffer.get(result);
    return result;
  }

  // Legacy API for PaxeNetwork compatibility
  public static byte[] decrypt(byte[] encrypted, byte[] sessionKey) {
    ByteBuffer input = ByteBuffer.wrap(encrypted);
    ByteBuffer output = ByteBuffer.allocate(encrypted.length);
    return decrypt(output, input, sessionKey);
  }

  public static void encryptStandard(ByteBuffer output, byte[] payload, byte[] sessionKey) {
    try {
      byte[] nonce = new byte[GCM_NONCE_LENGTH];
      RANDOM.get().nextBytes(nonce);

      output.put((byte)0);
      output.put(nonce);

      Cipher cipher = CIPHER.get();
      cipher.init(Cipher.ENCRYPT_MODE,
          new SecretKeySpec(sessionKey, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce));

      output.put(cipher.doFinal(payload));

    } catch (GeneralSecurityException e) {
      throw new SecurityException("Encryption failed", e);
    }
  }

  public static void dekEncrypt(ByteBuffer output, byte[] payload, byte[] sessionKey) {
    if (payload.length <= DEK_THRESHOLD) {
      encryptStandard(output, payload, sessionKey);
      return;
    }

    try {
      // Generate DEK
      KeyGenerator keyGen = KeyGenerator.getInstance("AES");
      keyGen.init(128);
      byte[] dekKey = keyGen.generateKey().getEncoded();

      // Generate nonces
      byte[] sessionNonce = new byte[GCM_NONCE_LENGTH];
      byte[] dekNonce = new byte[GCM_NONCE_LENGTH];
      RANDOM.get().nextBytes(sessionNonce);
      RANDOM.get().nextBytes(dekNonce);

      // Write flag and session nonce
      output.put(FLAG_DEK);
      output.put(sessionNonce);

      // Encrypt payload with DEK
      Cipher dekCipher = CIPHER.get();
      dekCipher.init(Cipher.ENCRYPT_MODE,
          new SecretKeySpec(dekKey, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH_BITS, dekNonce));
      byte[] encryptedPayload = dekCipher.doFinal(payload);

      // Create and encrypt envelope
      byte[] envelope = new byte[DEK_SIZE + GCM_NONCE_LENGTH + 2];
      ByteBuffer envelopeBuffer = ByteBuffer.wrap(envelope);
      envelopeBuffer.put(dekKey).put(dekNonce).putShort((short)payload.length);

      Cipher sessionCipher = CIPHER.get();
      sessionCipher.init(Cipher.ENCRYPT_MODE,
          new SecretKeySpec(sessionKey, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH_BITS, sessionNonce));

      output.put(sessionCipher.doFinal(envelope));
      output.put(encryptedPayload);

    } catch (GeneralSecurityException e) {
      throw new SecurityException("DEK encryption failed", e);
    }
  }

  public static byte[] decrypt(ByteBuffer output, ByteBuffer input, byte[] sessionKey) {
    if (input.remaining() < GCM_NONCE_LENGTH + 1) {
      throw new IllegalArgumentException("Input buffer too short for header");
    }

    byte flags = input.get();
    return (flags & FLAG_DEK) == 0 ?
        decryptStandard(input, sessionKey) :
        decryptDek(input, sessionKey);
  }

  private static byte[] decryptStandard(ByteBuffer input, byte[] sessionKey) {
    try {
      byte[] nonce = new byte[GCM_NONCE_LENGTH];
      input.get(nonce);

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

  private static byte[] decryptDek(ByteBuffer input, byte[] sessionKey) {
    try {
      if (input.remaining() < GCM_NONCE_LENGTH + DEK_SECTION_SIZE) {
        throw new IllegalArgumentException("Input buffer too short for DEK content");
      }

      byte[] sessionNonce = new byte[GCM_NONCE_LENGTH];
      input.get(sessionNonce);

      byte[] encryptedEnvelope = new byte[DEK_SECTION_SIZE];
      input.get(encryptedEnvelope);

      Cipher sessionCipher = CIPHER.get();
      sessionCipher.init(Cipher.DECRYPT_MODE,
          new SecretKeySpec(sessionKey, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH_BITS, sessionNonce));

      byte[] envelope = sessionCipher.doFinal(encryptedEnvelope);
      ByteBuffer envelopeBuffer = ByteBuffer.wrap(envelope);

      byte[] dekKey = new byte[DEK_SIZE];
      byte[] dekNonce = new byte[GCM_NONCE_LENGTH];
      envelopeBuffer.get(dekKey).get(dekNonce);
      short payloadLength = envelopeBuffer.getShort();

      if (input.remaining() < payloadLength + GCM_AUTH_TAG_LENGTH) {
        throw new IllegalArgumentException("Input buffer too short for payload");
      }

      byte[] encryptedPayload = new byte[input.remaining()];
      input.get(encryptedPayload);

      Cipher dekCipher = CIPHER.get();
      dekCipher.init(Cipher.DECRYPT_MODE,
          new SecretKeySpec(dekKey, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH_BITS, dekNonce));

      return dekCipher.doFinal(encryptedPayload);

    } catch (GeneralSecurityException e) {
      throw new SecurityException("DEK decryption failed", e);
    }
  }
}
