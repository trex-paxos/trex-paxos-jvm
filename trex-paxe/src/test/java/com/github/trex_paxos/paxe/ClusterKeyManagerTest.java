// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.junit.jupiter.api.Assertions.*;

class ClusterKeyManagerTest {

  @Test
  void acceptsThirtyTwoByteClusterPsk() {
    byte[] psk = NetworkTestHarness.generateClusterPsk();
    var manager = new ClusterKeyManager(psk);
    assertEquals((byte) 0, manager.currentEpoch());
    assertArrayEquals(psk, manager.keyForEpoch((byte) 0));
  }

  @Test
  void supportsEpochRotationOverlap() {
    byte[] epoch0 = NetworkTestHarness.generateClusterPsk();
    byte[] epoch1 = NetworkTestHarness.generateClusterPsk();
    var manager = new ClusterKeyManager(epoch0, (byte) 0);

    manager.installEpoch((byte) 1, epoch1);
    assertArrayEquals(epoch0, manager.keyForEpoch((byte) 0));
    assertArrayEquals(epoch1, manager.keyForEpoch((byte) 1));

    manager.setCurrentEpoch((byte) 1);
    assertEquals((byte) 1, manager.currentEpoch());

    manager.retireEpoch((byte) 0);
    assertFalse(manager.hasEpoch((byte) 0));
    assertTrue(manager.hasEpoch((byte) 1));
  }

  @Test
  void rejectsWrongKeySize() {
    byte[] tooShort = new byte[16];
    new SecureRandom().nextBytes(tooShort);
    assertThrows(IllegalArgumentException.class, () -> new ClusterKeyManager(tooShort));
  }
}
