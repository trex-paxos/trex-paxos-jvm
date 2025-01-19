package com.github.trex_paxos.paxe;

import javax.crypto.Cipher;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

public final class PaxeCrypto {
  static final int GCM_NONCE_LENGTH = 12;
  static final int GCM_TAG_LENGTH = 128;
  static final int DEK_SIZE = 16;      // AES-128
  static final int PAYLOAD_THRESHOLD = 64;
  static final int GCM_TAG_BYTES = GCM_TAG_LENGTH / 8;
  static final int DEK_SECTION_SIZE = GCM_NONCE_LENGTH + DEK_SIZE + GCM_TAG_BYTES; // 12 + 16 + 16 = 44 bytes;

  private static final ThreadLocal<SecureRandom> RANDOM = ThreadLocal.withInitial(SecureRandom::new);
  private static final ThreadLocal<Cipher> CIPHER = ThreadLocal.withInitial(() -> {
    try {
      return Cipher.getInstance("AES/GCM/NoPadding");
    } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
      throw new RuntimeException("Required crypto algorithm unavailable", e);
    }
  });

  /**
   * Encrypted payload format:
   * Direct: [flags:1][nonce:12][ciphertext:n][tag:16]
   * DEK:    [flags:1][dek_nonce:12][dek_ciphertext:16][dek_tag:16][nonce:12][ciphertext:n][tag:16]
   */
  public enum Mode {
    DIRECT(0x00),
    WITH_DEK(0x01);

    final byte flag;

    Mode(int flag) {
      this.flag = (byte) flag;
    }
  }

  public static byte[] encrypt(byte[] data, byte[] key) {
    return encrypt(data, key, data.length > PAYLOAD_THRESHOLD ? Mode.WITH_DEK : Mode.DIRECT);
  }

  public static byte[] encrypt(byte[] data, byte[] key, Mode mode) {
    if (mode == Mode.WITH_DEK) {
      byte[] dek = generateKey();
      byte[] encryptedDek = gcmEncrypt(dek, key);
      byte[] encryptedData = gcmEncrypt(data, dek);

      return ByteBuffer.allocate(1 + encryptedDek.length + encryptedData.length)
          .put(mode.flag)
          .put(encryptedDek)
          .put(encryptedData)
          .array();
    }

    byte[] encrypted = gcmEncrypt(data, key);
    return ByteBuffer.allocate(1 + encrypted.length)
        .put(mode.flag)
        .put(encrypted)
        .array();
  }

  public static byte[] decrypt(byte[] data, byte[] key) {
    ByteBuffer buffer = ByteBuffer.wrap(data);
    Mode mode = (buffer.get() == Mode.WITH_DEK.flag) ? Mode.WITH_DEK : Mode.DIRECT;

    if (mode == Mode.WITH_DEK) {
      byte[] encryptedDek = new byte[GCM_NONCE_LENGTH + DEK_SIZE + GCM_TAG_BYTES];
      buffer.get(encryptedDek);
      byte[] dek = gcmDecrypt(encryptedDek, key);

      byte[] encryptedData = new byte[buffer.remaining()];
      buffer.get(encryptedData);
      return gcmDecrypt(encryptedData, dek);
    }

    byte[] encryptedData = new byte[buffer.remaining()];
    buffer.get(encryptedData);
    return gcmDecrypt(encryptedData, key);
  }

  public static byte[] reencryptDek(byte[] data, byte[] oldKey, byte[] newKey) {
    ByteBuffer buffer = ByteBuffer.wrap(data);
    Mode mode = (buffer.get() == Mode.WITH_DEK.flag) ? Mode.WITH_DEK : Mode.DIRECT;

    if (mode != Mode.WITH_DEK) {
      throw new IllegalArgumentException("Cannot reencrypt DEK on direct encrypted data");
    }

    byte[] encryptedDek = new byte[GCM_NONCE_LENGTH + DEK_SIZE + GCM_TAG_BYTES];
    buffer.get(encryptedDek);
    byte[] dek = gcmDecrypt(encryptedDek, oldKey);

    byte[] reencryptedDek = gcmEncrypt(dek, newKey);
    return ByteBuffer.allocate(1 + reencryptedDek.length + buffer.remaining())
        .put(mode.flag)
        .put(reencryptedDek)
        .put(buffer)
        .array();
  }

  private static byte[] generateKey() {
    byte[] key = new byte[DEK_SIZE];
    RANDOM.get().nextBytes(key);
    return key;
  }

  private static byte[] gcmEncrypt(byte[] data, byte[] key) {
    try {
      byte[] nonce = new byte[GCM_NONCE_LENGTH];
      RANDOM.get().nextBytes(nonce);

      Cipher cipher = CIPHER.get();
      cipher.init(Cipher.ENCRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH, nonce));

      byte[] ciphertext = cipher.doFinal(data);
      return ByteBuffer.allocate(nonce.length + ciphertext.length)
          .put(nonce)
          .put(ciphertext)
          .array();
    } catch (GeneralSecurityException e) {
      throw new RuntimeException("GCM encryption failed", e);
    }
  }

  private static byte[] gcmDecrypt(byte[] data, byte[] key) {
    try {
      ByteBuffer buffer = ByteBuffer.wrap(data);
      byte[] nonce = new byte[GCM_NONCE_LENGTH];
      buffer.get(nonce);

      Cipher cipher = CIPHER.get();
      cipher.init(Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH, nonce));

      byte[] ciphertext = new byte[buffer.remaining()];
      buffer.get(ciphertext);
      return cipher.doFinal(ciphertext);
    } catch (GeneralSecurityException e) {
      throw new RuntimeException("GCM decryption failed", e);
    }
  }
}
