// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClusterIdTest {

  @Test
  void storesId() {
    assertEquals("us.west.test", new ClusterId("us.west.test").id());
  }

  @Test
  void rejectsNullId() {
    assertThrows(IllegalArgumentException.class, () -> new ClusterId(null));
  }
}
