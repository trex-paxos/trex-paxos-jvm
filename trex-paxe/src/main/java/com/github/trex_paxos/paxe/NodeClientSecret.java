package com.github.trex_paxos.paxe;

import java.util.Objects;

import com.github.trex_paxos.network.NodeId;
/// This is an Secure Remote Password secret for a node see RFC5054
public record NodeClientSecret(
  String srpIdentity,
  String password,
  byte[] salt  // 16 bytes required
) {
  public NodeClientSecret {
    Objects.requireNonNull(srpIdentity, "srpIdentity required");
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
