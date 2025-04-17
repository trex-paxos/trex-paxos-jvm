// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/// In Java 24 which is not yet released we will have JEP 478: Key Derivation Function API (Preview)
/// As that is not available, and only when using a week key logarithm, not the preferential SHA-256, we will use the
/// following HKDF implementation.
public class SimpleHKDF {
  public static byte[] extract(byte[] salt, byte[] ikm) throws Exception {
    if (salt == null || salt.length == 0) {
      salt = new byte[32]; // Default to a zero-filled array for SHA-256
    }
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(salt, "HmacSHA256"));
    return mac.doFinal(ikm);
  }

  public static byte[] expand(byte[] prk, byte[] info, int length) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(prk, "HmacSHA256"));

    byte[] result = new byte[length];
    byte[] t = new byte[0];
    int offset = 0;
    for (int i = 1; offset < length; i++) {
      mac.update(t);
      if (info != null) {
        mac.update(info);
      }
      mac.update((byte) i);
      t = mac.doFinal();
      int chunkLength = Math.min(t.length, length - offset);
      System.arraycopy(t, 0, result, offset, chunkLength);
      offset += chunkLength;
    }
    return result;
  }
}
