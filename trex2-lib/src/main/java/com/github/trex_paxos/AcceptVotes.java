package com.github.trex_paxos;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

record AcceptVotes(Accept accept, Map<Byte, AcceptResponse> responses, boolean chosen) {
 AcceptVotes(Accept accept) {
  this(accept, new HashMap<>(), false);
}

static AcceptVotes chosen(Accept accept) {
        return new AcceptVotes(accept, Collections.emptyMap(), true);
    }
}

