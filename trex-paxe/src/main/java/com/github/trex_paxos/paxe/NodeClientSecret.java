package com.github.trex_paxos.paxe;

import java.util.Objects;

public record NodeClientSecret(
  String srpIdenity,
  String password,
  byte[] salt  // 16 bytes required
) {
  public NodeClientSecret {
    Objects.requireNonNull(srpIdenity, "srpIdenity required");
    Objects.requireNonNull(password, "password required");
    Objects.requireNonNull(salt, "salt required");
    if(salt.length != 16) {
      throw new IllegalArgumentException("salt must be 16 bytes");
    }
  }
  public NodeClientSecret(ClusterId clusterId, NodeId id, String password, byte[] salt) {
    this(id.id() + "@" + clusterId.id(), password, salt);
  }
}