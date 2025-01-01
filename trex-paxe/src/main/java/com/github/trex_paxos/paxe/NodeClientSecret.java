package com.github.trex_paxos.paxe;

import java.util.Objects;
import java.util.UUID;

public record NodeClientSecret(
  ClusterId clusterId,
  NodeId identity, 
  UUID password,
  byte[] salt  // 16 bytes required
) {
  public NodeClientSecret {
    Objects.requireNonNull(clusterId, "clusterId required");
    Objects.requireNonNull(identity, "identity required");
    Objects.requireNonNull(password, "password required");
    Objects.requireNonNull(salt, "salt required");
    if(salt.length != 16) {
      throw new IllegalArgumentException("salt must be 16 bytes");
    }
  }
}