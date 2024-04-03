package com.github.trex_paxos;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

// TODO we must timeout on not hearing back any responses
record AcceptVotes(Accept accept, Map<Integer, AcceptResponse> responses, boolean chosen){
 AcceptVotes(Accept accept) {
  this(accept, new HashMap<>(), false);
}

static AcceptVotes chosen(Accept accept) {
        return new AcceptVotes(accept, Collections.emptyMap(), true);
    }
}

