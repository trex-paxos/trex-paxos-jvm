package com.github.trex_paxos.paxe;

import java.util.Objects;

public record PaxeSecurityConfig(
  NodeClientSecret localSecret,
  java.util.function.Supplier<java.util.Map<NodeId,NodeVerifier>> verifierLookup 
) {
  public PaxeSecurityConfig {
    Objects.requireNonNull(localSecret, "localSecret required");
    Objects.requireNonNull(verifierLookup, "verifierLookup required"); 
  }
}