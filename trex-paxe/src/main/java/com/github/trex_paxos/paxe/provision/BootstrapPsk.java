// SPDX-FileCopyrightText: 2024 - 2025 Simon Massey
// SPDX-License-Identifier: Apache-2.0
package com.github.trex_paxos.paxe.provision;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/// Bootstrap credential for TLS 1.3 external-PSK authentication on the provisioning channel.
/// This authenticates the control plane only and must not be installed as the PAXE cluster data key.
public record BootstrapPsk(String identity, byte[] key) {

  public static final int KEY_SIZE = 32;

  public BootstrapPsk {
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(key, "key");
    if (identity.isBlank()) {
      throw new IllegalArgumentException("Bootstrap PSK identity must not be blank");
    }
    if (key.length != KEY_SIZE) {
      throw new IllegalArgumentException("Bootstrap PSK must be " + KEY_SIZE + " bytes");
    }
    key = key.clone();
  }

  public static BootstrapPsk fromHex(String identity, String hex) {
    if (hex.length() != KEY_SIZE * 2) {
      throw new IllegalArgumentException("Bootstrap PSK must be " + (KEY_SIZE * 2) + " hex characters");
    }
    return new BootstrapPsk(identity, HexFormat.of().parseHex(hex));
  }

  public String keyHex() {
    return HexFormat.of().formatHex(key);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof BootstrapPsk that)) return false;
    return identity.equals(that.identity) && Arrays.equals(key, that.key);
  }

  @Override
  public int hashCode() {
    return Objects.hash(identity, Arrays.hashCode(key));
  }

  @Override
  public String toString() {
    return "BootstrapPsk{identity='" + identity + "'}";
  }
}
