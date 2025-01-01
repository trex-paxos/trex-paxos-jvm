package com.github.trex_paxos.paxe;

import java.util.Objects;

public record NodeClientSecret(
  ClusterId clusterId,
  NodeId id, 
  String password,
  byte[] salt  // 16 bytes required
) {
  public NodeClientSecret {
    Objects.requireNonNull(clusterId, "clusterId required");
    Objects.requireNonNull(id, "identity required");
    Objects.requireNonNull(password, "password required");
    Objects.requireNonNull(salt, "salt required");
    if(salt.length != 16) {
      throw new IllegalArgumentException("salt must be 16 bytes");
    }
  }
  public String srpIdenity() {
    return id.id() + "@" + clusterId.id();
  }
}