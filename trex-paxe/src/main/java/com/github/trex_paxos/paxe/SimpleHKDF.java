/*
 * Copyright 2024 - 2025 Simon Massey
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
