package com.github.trex_paxos;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * A record of the votes received for an accept request. Due to lost messages we may get chosen values that we
 * cannot commit that will be stored until we know that they can be committed.
 */
record AcceptVotes(Accept accept, Map<Byte, AcceptResponse> responses, boolean chosen) {
  AcceptVotes(Accept accept) {
    this(accept, new HashMap<>(), false);
  }

  static AcceptVotes chosen(Accept accept) {
    return new AcceptVotes(accept, Collections.emptyMap(), true);
  }
}

