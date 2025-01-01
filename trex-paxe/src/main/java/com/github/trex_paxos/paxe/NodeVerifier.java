package com.github.trex_paxos.paxe;

import java.util.Objects;

public record NodeVerifier(
  NodeId identity,
  String verifier  // RFC 5054 verifier as hex string
) {
  public NodeVerifier {
    Objects.requireNonNull(identity, "identity required");
    Objects.requireNonNull(verifier, "verifier required");
  }
}