// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IdentityTest {

  @Test
  void parsesNodeAtCluster() {
    var identity = Identity.from("42@test.cluster");
    assertEquals((short) 42, identity.nodeId());
    assertEquals("test.cluster", identity.cluster());
    assertEquals("42@test.cluster", identity.full());
  }

  @Test
  void rejectsMalformedIdentity() {
    assertThrows(IllegalArgumentException.class, () -> Identity.from("missing-at-sign"));
    assertThrows(IllegalArgumentException.class, () -> Identity.from("not-a-number@cluster"));
  }
}
