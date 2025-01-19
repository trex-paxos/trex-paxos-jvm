package com.github.trex_paxos.paxe;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;

public final class PaxeCrypto {
  private static final int GCM_NONCE_LENGTH = 12;
  private static final int GCM_TAG_LENGTH = 128; // bits
  private static final ThreadLocal<SecureRandom> RANDOM =
      ThreadLocal.withInitial(SecureRandom::new);

  private PaxeCrypto() {} // Utility class

  public static byte[] encrypt(byte[] data, byte[] key) {
    try {
      byte[] nonce = new byte[GCM_NONCE_LENGTH];
      RANDOM.get().nextBytes(nonce);

      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH, nonce));

      byte[] ciphertext = cipher.doFinal(data);

      // Combine nonce + ciphertext
      return ByteBuffer.allocate(nonce.length + ciphertext.length)
          .put(nonce)
          .put(ciphertext)
          .array();

    } catch (GeneralSecurityException e) {
      throw new RuntimeException("Encryption failed", e);
    }
  }

  public static byte[] decrypt(byte[] data, byte[] key) {
    try {
      ByteBuffer buffer = ByteBuffer.wrap(data);

      byte[] nonce = new byte[GCM_NONCE_LENGTH];
      buffer.get(nonce);

      byte[] ciphertext = new byte[buffer.remaining()];
      buffer.get(ciphertext);

      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE,
          new SecretKeySpec(key, "AES"),
          new GCMParameterSpec(GCM_TAG_LENGTH, nonce));

      return cipher.doFinal(ciphertext);

    } catch (GeneralSecurityException e) {
      throw new RuntimeException("Decryption failed", e);
    }
  }
}
