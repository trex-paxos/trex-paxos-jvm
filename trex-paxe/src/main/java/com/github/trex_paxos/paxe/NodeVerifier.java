package com.github.trex_paxos.paxe;

import java.util.Objects;

/// A verifier for a node in the cluster
/// @param identity RFC 5054 identity which is the node id and cluster id concatenated with '@' such as "1@test.cluster"
/// @param verifier RFC 5054 verifier as hex string
public record NodeVerifier(
  String identity,
  String verifier
) {
  public NodeVerifier {
    Objects.requireNonNull(identity, "identity required");
    Objects.requireNonNull(verifier, "verifier required");
  }
}