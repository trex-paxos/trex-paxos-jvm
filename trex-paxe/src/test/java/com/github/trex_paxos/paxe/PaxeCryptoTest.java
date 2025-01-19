package com.github.trex_paxos.paxe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaxeCryptoTest {

  @Test
  void shouldUseDekForLargePayloads() {
    byte[] key = new byte[16];
    byte[] smallData = new byte[PaxeCrypto.PAYLOAD_THRESHOLD - 1];
    byte[] largeData = new byte[PaxeCrypto.PAYLOAD_THRESHOLD + 1];

    byte[] encryptedSmall = PaxeCrypto.encrypt(smallData, key);
    byte[] encryptedLarge = PaxeCrypto.encrypt(largeData, key);

    assertEquals(PaxeCrypto.Mode.DIRECT.flag, encryptedSmall[0]);
    assertEquals(PaxeCrypto.Mode.WITH_DEK.flag, encryptedLarge[0]);

    assertArrayEquals(smallData, PaxeCrypto.decrypt(encryptedSmall, key));
    assertArrayEquals(largeData, PaxeCrypto.decrypt(encryptedLarge, key));
  }

  @Test
  void shouldReencryptDekWithNewKey() {
    byte[] oldKey = new byte[16];
    byte[] newKey = new byte[16];
    newKey[0] = 1; // Make it different

    byte[] largeData = new byte[PaxeCrypto.PAYLOAD_THRESHOLD + 1];
    byte[] encrypted = PaxeCrypto.encrypt(largeData, oldKey);
    byte[] reencrypted = PaxeCrypto.reencryptDek(encrypted, oldKey, newKey);

    // Original decryption should fail
    assertThrows(RuntimeException.class, () -> PaxeCrypto.decrypt(reencrypted, oldKey));

    // New key decryption should work
    assertArrayEquals(largeData, PaxeCrypto.decrypt(reencrypted, newKey));
  }

  @Test
  void shouldFailReencryptForDirectMode() {
    byte[] key = new byte[16];
    byte[] data = new byte[PaxeCrypto.PAYLOAD_THRESHOLD - 1];
    byte[] encrypted = PaxeCrypto.encrypt(data, key);

    assertThrows(IllegalArgumentException.class,
        () -> PaxeCrypto.reencryptDek(encrypted, key, key));
  }
}
