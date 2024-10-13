package com.github.trex_paxos.msg;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A record of the votes received by a node from other cluster members.
 */
public record AcceptVotes(Accept accept, Map<Byte, AcceptResponse> responses, boolean chosen) {
  public AcceptVotes(Accept accept) {
    this(accept, new HashMap<>(), false);
  }

  public static AcceptVotes chosen(Accept accept) {
    return new AcceptVotes(accept, Collections.emptyMap(), true);
  }
}

